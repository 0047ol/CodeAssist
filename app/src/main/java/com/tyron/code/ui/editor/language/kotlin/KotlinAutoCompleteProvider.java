package com.tyron.code.ui.editor.language.kotlin;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.tyron.ProjectManager;
import com.tyron.completion.model.CompletionList;
import com.tyron.kotlin_completion.CompletionEngine;
import com.tyron.kotlin_completion.compiler.CompletionKind;

import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile;

import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.rosemoe.sora.data.CompletionItem;
import io.github.rosemoe.sora.interfaces.AutoCompleteProvider;
import io.github.rosemoe.sora.text.TextAnalyzeResult;
import io.github.rosemoe.sora.widget.CodeEditor;

public class KotlinAutoCompleteProvider implements AutoCompleteProvider {

    private final CodeEditor mEditor;
    private final SharedPreferences mPreferences;
    com.tyron.psi.completion.CompletionEngine engine;


    public KotlinAutoCompleteProvider(CodeEditor editor) {
        mEditor = editor;
        mPreferences = PreferenceManager.getDefaultSharedPreferences(editor.getContext());
    }

    @Override
    public List<CompletionItem> getAutoCompleteItems(String prefix, TextAnalyzeResult analyzeResult, int line, int column) throws InterruptedException {
        if (com.tyron.completion.provider.CompletionEngine.isIndexing()) {
            return null;
        }

        if (!mPreferences.getBoolean("code_editor_completion", true)) {
            return Collections.emptyList();
        }

        try {
            if (engine == null) {
//                CompletionList list = CompletionEngine.getInstance(ProjectManager.getInstance().getCurrentProject())
//                        .complete(mEditor.getCurrentFile(), mEditor.getText().toString(), mEditor.getCursor().getLeft());
//                List<CompletionItem> result = new ArrayList<>();
//                List<com.tyron.completion.model.CompletionItem> item = list.items;
//                for (com.tyron.completion.model.CompletionItem comp : item) {
//                    result.add(new CompletionItem(comp));
//                }
            } else {

            }

            if (engine == null) {
                engine = new com.tyron.psi.completion.CompletionEngine(CompletionEngine.getInstance(ProjectManager.getInstance().getCurrentProject())
                        .getSourcePath()
                        .getCompilerClassPath()
                        .getCompiler().getDefaultCompileEnvironment().getEnvironment().getProjectEnvironment());
            }

            PsiJavaFile javaFile = CompletionEngine.getInstance(ProjectManager.getInstance().getCurrentProject())
                    .getSourcePath()
                    .getCompilerClassPath()
                    .getCompiler()
                    .createJavaFile(mEditor.getText().toString(), Paths.get("Main.java"), CompletionKind.DEFAULT);
            engine.complete(javaFile, javaFile.findElementAt(mEditor.getCursor().getLeft() - 1), mEditor.getCursor().getLeft());
            return null;
        } catch (ProcessCanceledException e) {
            return null;
        }
    }
}
