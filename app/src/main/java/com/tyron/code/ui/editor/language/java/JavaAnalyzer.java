package com.tyron.code.ui.editor.language.java;

import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;

import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.model.SourceFileObject;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.BuildConfig;
import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorView;
import com.tyron.code.ui.editor.language.AbstractCodeAnalyzer;
import com.tyron.code.ui.editor.language.HighlightUtil;
import com.tyron.code.ui.editor.language.kotlin.KotlinLexer;
import com.tyron.code.ui.editor.language.textmate.BaseTextmateAnalyzer;
import com.tyron.code.ui.editor.language.textmate.DiagnosticTextmateAnalyzer;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.common.util.Debouncer;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.provider.CompletionEngine;
import com.tyron.completion.java.util.ErrorCodes;
import com.tyron.completion.java.util.TreeUtil;
import com.tyron.completion.progress.ProcessCanceledException;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.editor.Editor;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;
import org.jetbrains.kotlin.com.intellij.util.ReflectionUtil;
import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.source.tree.BlockTree;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.api.ClientCodeWrapper;
import org.openjdk.tools.javac.tree.JCTree;
import org.openjdk.tools.javac.util.JCDiagnostic;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;
import java.util.stream.Collectors;

import io.github.rosemoe.editor.langs.java.JavaTextTokenizer;
import io.github.rosemoe.editor.langs.java.Tokens;
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.lang.styling.CodeBlock;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.langs.textmate.theme.TextMateColorScheme;
import io.github.rosemoe.sora.text.LineNumberCalculator;
import io.github.rosemoe.sora.textmate.core.theme.IRawTheme;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

public class JavaAnalyzer extends DiagnosticTextmateAnalyzer {

    public static JavaAnalyzer create(Editor editor) {
        try  {
        AssetManager assetManager = ApplicationLoader.applicationContext.getAssets();
        return new JavaAnalyzer(editor, "java.tmLanguage.json",
                         assetManager.open(
                                 "textmate/java" +
                                 "/syntaxes/java" +
                                 ".tmLanguage.json"),
                         new InputStreamReader(
                                 assetManager.open(
                                         "textmate/java/language-configuration.json")),
                         ((TextMateColorScheme) ((CodeEditorView) editor).getColorScheme()).getRawTheme());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    private static final Debouncer sDebouncer = new Debouncer(Duration.ofMillis(700));
    private static final String TAG = JavaAnalyzer.class.getSimpleName();

    private final WeakReference<Editor> mEditorReference;
    private List<DiagnosticWrapper> mDiagnostics;
    private final List<DiagnosticWrapper> mPreviousDiagnostics = new ArrayList<>();
    private final SharedPreferences mPreferences;

    public JavaAnalyzer(Editor editor,
                        String grammarName,
                        InputStream grammarIns,
                        Reader languageConfiguration,
                        IRawTheme theme) throws Exception {
        super(editor, grammarName, grammarIns, languageConfiguration, theme);

        mEditorReference = new WeakReference<>(editor);
        mPreferences = ApplicationLoader.getDefaultPreferences();
    }

    @Override
    public void analyzeInBackground(CharSequence contents) {
        sDebouncer.cancel();
        sDebouncer.schedule(cancel -> {
            doAnalyzeInBackground(cancel, contents);
            return Unit.INSTANCE;
        });
    }

    private JavaCompilerService getCompiler(Editor editor) {
        Project project = ProjectManager.getInstance()
                .getCurrentProject();
        if (project == null) {
            return null;
        }
        if (project.isCompiling()) {
            return null;
        }
        Module module = project.getModule(editor.getCurrentFile());
        if (module instanceof JavaModule) {
            JavaCompilerProvider provider = CompilerService.getInstance()
                    .getIndex(JavaCompilerProvider.KEY);
            if (provider != null) {
                return provider.getCompiler(project, (JavaModule) module);
            }
        }
        return null;
    }

    private void doAnalyzeInBackground(Function0<Boolean> cancel, CharSequence contents) {
        Editor editor = mEditorReference.get();
        if (editor == null) {
            return;
        }
        if (cancel.invoke()) {
            return;
        }

        // do not compile the file if it not yet closed as it will cause issues when
        // compiling multiple files at the same time
        if (mPreferences.getBoolean(SharedPreferenceKeys.JAVA_ERROR_HIGHLIGHTING, true)) {
            JavaCompilerService service = getCompiler(editor);
            if (service != null) {
                File currentFile = editor.getCurrentFile();
                if (currentFile == null) {
                    return;
                }
                Module module = ProjectManager.getInstance().getCurrentProject().getModule(currentFile);
                if (!module.getFileManager().isOpened(currentFile)) {
                    return;
                }
                try {
                    ProgressManager.getInstance()
                            .runLater(() -> editor.setAnalyzing(true));
                    SourceFileObject sourceFileObject = new SourceFileObject(currentFile
                                                                                     .toPath(),
                                                                             contents.toString(),
                                                                             Instant.now());
                    CompilerContainer container = service.compile(Collections.singletonList(sourceFileObject));
                    container.run(task -> {
                        if (!cancel.invoke()) {
                            List<DiagnosticWrapper> collect = task.diagnostics.stream()
                                    .map(d -> modifyDiagnostic(task, d))
                                    .peek(it -> ProgressManager.checkCanceled())
                                    .collect(Collectors.toList());
                            editor.setDiagnostics(collect);

                            ProgressManager.getInstance()
                                    .runLater(() -> editor.setAnalyzing(false), 300);
                        }
                    });
                } catch (Throwable e) {
                    if (e instanceof ProcessCanceledException) {
                        throw e;
                    }
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG, "Unable to get diagnostics", e);
                    }
                    service.destroy();
                    ProgressManager.getInstance()
                            .runLater(() -> editor.setAnalyzing(false));
                }
            }
        }
    }

    private DiagnosticWrapper modifyDiagnostic(CompileTask task, Diagnostic<? extends JavaFileObject> diagnostic) {
        DiagnosticWrapper wrapped = new DiagnosticWrapper(diagnostic);

        if (diagnostic instanceof ClientCodeWrapper.DiagnosticSourceUnwrapper) {
            Trees trees = Trees.instance(task.task);
            SourcePositions positions = trees.getSourcePositions();

            JCDiagnostic jcDiagnostic = ((ClientCodeWrapper.DiagnosticSourceUnwrapper) diagnostic).d;
            JCDiagnostic.DiagnosticPosition diagnosticPosition =
                    jcDiagnostic.getDiagnosticPosition();
            JCTree tree = diagnosticPosition.getTree();

            if (tree != null) {
                TreePath treePath = trees.getPath(task.root(), tree);
                String code = jcDiagnostic.getCode();

                long start = diagnostic.getStartPosition();
                long end = diagnostic.getEndPosition();
                switch (code) {
                    case ErrorCodes.MISSING_RETURN_STATEMENT:
                        TreePath block = TreeUtil.findParentOfType(treePath, BlockTree.class);
                        if (block != null) {
                            // show error span only at the end parenthesis
                            end = positions.getEndPosition(task.root(), block.getLeaf()) + 1;
                            start = end - 2;
                        }
                        break;
                    case ErrorCodes.DEPRECATED:
                        if (treePath.getLeaf()
                                    .getKind() == Tree.Kind.METHOD) {
                            MethodTree methodTree = (MethodTree) treePath.getLeaf();
                            if (methodTree.getBody() != null) {
                                start = positions.getStartPosition(task.root(), methodTree);
                                end = positions.getStartPosition(task.root(), methodTree.getBody());
                            }
                        }
                        break;
                }

                wrapped.setStartPosition(start);
                wrapped.setEndPosition(end);
            }
        }
        return wrapped;
    }
}