package com.tyron.code.ui.editor.impl.text.rosemoe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.tyron.actions.DataContext;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;
import com.tyron.code.ui.editor.CodeAssistCompletionAdapter;
import com.tyron.code.ui.editor.CodeAssistCompletionWindow;
import com.tyron.code.ui.editor.EditorViewModel;
import com.tyron.code.ui.editor.NoOpTextActionWindow;
import com.tyron.code.ui.editor.language.DiagnosticAnalyzeManager;
import com.tyron.code.ui.editor.language.DiagnosticSpanMapUpdater;
import com.tyron.code.ui.editor.language.HighlightUtil;
import com.tyron.code.ui.editor.language.textmate.DiagnosticTextmateAnalyzer;
import com.tyron.code.ui.editor.language.xml.LanguageXML;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.completion.xml.util.DOMUtils;
import com.tyron.editor.Caret;
import com.tyron.editor.CharPosition;
import com.tyron.editor.Content;
import com.tyron.editor.Editor;

import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.dom.DOMParser;
import org.jetbrains.kotlin.com.intellij.util.ReflectionUtil;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.text.TextUtils;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.SymbolPairMatch;
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion;
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import io.github.rosemoe.sora2.text.EditorUtil;

public class CodeEditorView extends CodeEditor implements Editor {

    private static final Field sFormatThreadField;

    static {
        try {
            sFormatThreadField = CodeEditor.class.getDeclaredField("mFormatThread");
            sFormatThreadField.setAccessible(true);
        } catch (Throwable e) {
            throw new Error(e);
        }
    }
    private final Set<Character> IGNORED_PAIR_ENDS = ImmutableSet.<Character>builder().add(')')
            .add(']')
            .add('"')
            .add('>')
            .add('\'')
            .add(';')
            .build();

    private boolean mIsBackgroundAnalysisEnabled;

    private List<DiagnosticWrapper> mDiagnostics;
    private Consumer<List<DiagnosticWrapper>> mDiagnosticsListener;
    private File mCurrentFile;
    private EditorViewModel mViewModel;

    private final Paint mDiagnosticPaint;

    public CodeEditorView(Context context) {
        this(DataContext.wrap(context), null);
    }

    public CodeEditorView(Context context, AttributeSet attrs) {
        this(DataContext.wrap(context), attrs, 0);
    }

    public CodeEditorView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(DataContext.wrap(context), attrs, defStyleAttr, 0);
    }

    public CodeEditorView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(DataContext.wrap(context), attrs, defStyleAttr, defStyleRes);

        mDiagnosticPaint = new Paint();
        mDiagnosticPaint.setStrokeWidth(getDpUnit() * 2);

        init();
    }

    @Nullable
    @Override
    public Project getProject() {
        return ProjectManager.getInstance()
                .getCurrentProject();
    }

    @Override
    public void setEditorLanguage(@Nullable Language lang) {
        super.setEditorLanguage(lang);

        if (lang != null) {
            // languages should have an option to declare their own tab width
            try {
                Class<? extends Language> aClass = lang.getClass();
                Method method = ReflectionUtil.getDeclaredMethod(aClass, "getTabWidth");
                if (method != null) {
                    Object invoke = method.invoke(getEditorLanguage());
                    if (invoke instanceof Integer) {
                        setTabWidth((Integer) invoke);
                    }
                }
            } catch (Throwable e) {
                // use default
            }
        }
    }

    private void init() {
        CodeAssistCompletionWindow window = new CodeAssistCompletionWindow(this);
        window.setAdapter(new CodeAssistCompletionAdapter());
        replaceComponent(EditorAutoCompletion.class, window);
        replaceComponent(EditorTextActionWindow.class, new NoOpTextActionWindow(this));
    }

    @Override
    public void setColorScheme(@NonNull EditorColorScheme colors) {
        super.setColorScheme(colors);
    }

    @Override
    public List<DiagnosticWrapper> getDiagnostics() {
        return mDiagnostics;
    }

    @Override
    public void setDiagnostics(List<DiagnosticWrapper> diagnostics) {
        mDiagnostics = diagnostics;

        AnalyzeManager manager = getEditorLanguage().getAnalyzeManager();
        if (manager instanceof DiagnosticTextmateAnalyzer) {
            ((DiagnosticTextmateAnalyzer) manager).setDiagnostics(this, diagnostics);
        }

        if (mDiagnosticsListener != null) {
            mDiagnosticsListener.accept(mDiagnostics);
        }

        invalidate();
    }

    public void setDiagnosticsListener(Consumer<List<DiagnosticWrapper>> listener) {
        mDiagnosticsListener = listener;
    }

    @Override
    public File getCurrentFile() {
        return mCurrentFile;
    }

    @Override
    public void openFile(File file) {
        mCurrentFile = file;
    }

    @Override
    public CharPosition getCharPosition(int index) {
        io.github.rosemoe.sora.text.CharPosition charPosition = getText().getIndexer()
                .getCharPosition(index);
        return new CharPosition(charPosition.line, charPosition.column);
    }

    @Override
    public int getCharIndex(int line, int column) {
        return getText().getCharIndex(line, column);
    }

    @Override
    public boolean useTab() {
        //noinspection ConstantConditions, editor language can be null
        if (getEditorLanguage() == null) {
            // enabled by default
            return true;
        }

        return getEditorLanguage().useTab();
    }

    @Override
    public int getTabCount() {
        return getTabWidth();
    }

    @Override
    public void insert(int line, int column, String string) {
        getText().insert(line, column, string);
    }

    @Override
    public void commitText(CharSequence text) {
        super.commitText(text);
    }

    @Override
    public void commitText(CharSequence text, boolean applyAutoIndent) {
        if (text.length() == 1) {
            char currentChar = getText().charAt(getCursor().getLeft());
            char c = text.charAt(0);
            if (IGNORED_PAIR_ENDS.contains(c) && c == currentChar) {
                // ignored pair end, just move the cursor over the character
                setSelection(getCursor().getLeftLine(), getCursor().getLeftColumn() + 1);
                return;
            }
        }
        super.commitText(text, applyAutoIndent);

        if (text.length() == 1) {
            char c = text.charAt(0);
            handleAutoInsert(c);
        }
    }

    private void handleAutoInsert(char c) {
        if (getEditorLanguage() instanceof LanguageXML) {
            if (c != '>' && c != '/') {
                return;
            }
            boolean full = c == '>';

            DOMDocument document = DOMParser.getInstance()
                    .parse(getText().toString(), "", null);
            DOMNode nodeAt = document.findNodeAt(getCursor().getLeft());
            if (!DOMUtils.isClosed(nodeAt) && nodeAt.getNodeName() != null) {
                String insertText = full ? "</" + nodeAt.getNodeName() + ">" : ">";
                commitText(insertText);
                setSelection(getCursor().getLeftLine(),
                             getCursor().getLeftColumn() - (full ? insertText.length() : 0));
            }
        }
    }

    @Override
    public void deleteText() {
        Cursor cursor = getCursor();
        if (!cursor.isSelected()) {
            io.github.rosemoe.sora.text.Content text = getText();
            int startIndex = cursor.getLeft();
            if (startIndex - 1 >= 0) {
                char deleteChar = text.charAt(startIndex - 1);
                char afterChar = text.charAt(startIndex);
                SymbolPairMatch.Replacement replacement = null;

                SymbolPairMatch pairs = getEditorLanguage().getSymbolPairs();
                if (pairs != null) {
                    replacement = pairs.getCompletion(deleteChar);
                }
                if (replacement != null) {
                    if (("" + deleteChar + afterChar + "").equals(replacement.text)) {
                        text.delete(startIndex - 1, startIndex + 1);
                        return;
                    }
                }
            }
        }
        super.deleteText();
    }

    @Override
    public void insertMultilineString(int line, int column, String string) {
        String currentLine = getText().getLineString(line);

        String[] lines = string.split("\\n");
        if (lines.length == 0) {
            return;
        }
        int count = TextUtils.countLeadingSpaceCount(currentLine, getTabWidth());
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();

            int advance = EditorUtil.getFormatIndent(getEditorLanguage(), trimmed);

            if (advance < 0) {
                count += advance;
            }

            if (i != 0) {
                String indent = TextUtils.createIndent(count, getTabWidth(), useTab());
                trimmed = indent + trimmed;
            }

            lines[i] = trimmed;

            if (advance > 0) {
                count += advance;
            }
        }

        String textToInsert = String.join("\n", lines);
        getText().insert(line, column, textToInsert);
    }

    @Override
    public void delete(int startLine, int startColumn, int endLine, int endColumn) {
        getText().delete(startLine, startColumn, endLine, endColumn);
    }

    @Override
    public void delete(int startIndex, int endIndex) {
        getText().delete(startIndex, endIndex);
    }

    @Override
    public void replace(int line, int column, int endLine, int endColumn, String string) {
        getText().replace(line, column, endLine, endColumn, string);
    }

    @Override
    public void setSelection(int line, int column) {
        super.setSelection(line, column);
    }

    @Override
    public void setSelectionRegion(int lineLeft, int columnLeft, int lineRight, int columnRight) {
        CodeEditorView.super.setSelectionRegion(lineLeft, columnLeft, lineRight, columnRight);
    }

    @Override
    public void setSelectionRegion(int startIndex, int endIndex) {
        CharPosition start = getCharPosition(startIndex);
        CharPosition end = getCharPosition(endIndex);
        CodeEditorView.super.setSelectionRegion(start.getLine(), start.getColumn(), end.getLine(),
                                                end.getColumn());
    }

    @Override
    public void beginBatchEdit() {
        getText().beginBatchEdit();
    }

    @Override
    public void endBatchEdit() {
        getText().endBatchEdit();
    }

    @Override
    public synchronized boolean formatCodeAsync() {
        return CodeEditorView.super.formatCodeAsync();
    }

    public boolean isFormatting() {
        try {
            return sFormatThreadField.get(this) != null;
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    @Override
    public synchronized boolean formatCodeAsync(int start, int end) {
//        CodeEditorView.super.formatCodeAsync();
//        return CodeEditorView.super.formatCodeAsync(start, end);
        return false;
    }

    @Override
    public Caret getCaret() {
        return new CursorWrapper(getCursor());
    }

    @Override
    public Content getContent() {
        return new ContentWrapper(CodeEditorView.this.getText());
    }

    /**
     * Background analysis can sometimes be expensive.
     * Set whether background analysis should be enabled for this editor.
     */
    public void setBackgroundAnalysisEnabled(boolean enabled) {
        mIsBackgroundAnalysisEnabled = enabled;
    }

    @Override
    public void rerunAnalysis() {
        //noinspection ConstantConditions
        if (getEditorLanguage() != null) {
            AnalyzeManager analyzeManager = getEditorLanguage().getAnalyzeManager();
            Project project = ProjectManager.getInstance()
                    .getCurrentProject();

            if (analyzeManager instanceof DiagnosticTextmateAnalyzer) {
                if (isBackgroundAnalysisEnabled() && (project != null && !project.isCompiling())) {
                    ((DiagnosticTextmateAnalyzer) analyzeManager).rerunWithBg();
                } else {
                    ((DiagnosticTextmateAnalyzer) analyzeManager).rerunWithoutBg();
                }
            } else {
                analyzeManager.rerun();
            }
        }
    }

    @Override
    public boolean isBackgroundAnalysisEnabled() {
        return mIsBackgroundAnalysisEnabled;
    }

    public void setAnalyzing(boolean analyzing) {
        if (mViewModel != null) {
            mViewModel.setAnalyzeState(analyzing);
        }
    }

    public void setViewModel(EditorViewModel editorViewModel) {
        mViewModel = editorViewModel;
    }

    @Override
    public void drawView(Canvas canvas) {
        super.drawView(canvas);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mDiagnostics == null || isFormatting()) {
            return;
        }
        for (DiagnosticWrapper d : mDiagnostics) {
            if (!DiagnosticSpanMapUpdater.isValid(d)) {
                continue;
            }
            if (d.getStartPosition() > getText().length()) {
                continue;
            }
            if (d.getEndPosition() > getText().length()) {
                continue;
            }

            CharPosition startPosition = getCharPosition((int) d.getStartPosition());
            CharPosition endPosition = getCharPosition((int) d.getEndPosition());

            if (!isRowVisible(startPosition.getLine()) || !isRowVisible(endPosition.getLine())) {
                continue;
            }
            setDiagnosticColor(d);
            if (startPosition.getLine() == endPosition.getLine()) {
                drawSingleLineDiagnostic(canvas, startPosition, endPosition);
            } else {
                drawMultiLineDiagnostic(canvas, startPosition, endPosition);
            }
        }
    }

    private void drawSingleLineDiagnostic(Canvas canvas, CharPosition startPosition, CharPosition endPosition) {
        float startX = getCharOffsetX(startPosition.getLine(), startPosition.getColumn());
        float startY = getCharOffsetY(startPosition.getLine(), startPosition.getColumn());
        float endX = getCharOffsetX(endPosition.getLine(), endPosition.getColumn());
        float endY = getCharOffsetY(endPosition.getLine(), endPosition.getColumn());

        if (startPosition.getColumn() == endPosition.getColumn()) {
            // expand a little bit further
            endX += getDpUnit() * 2;
        }
        drawSquigglyLine(canvas, startX, startY, endX, endY);
    }

    private void drawMultiLineDiagnostic(Canvas canvas, CharPosition start, CharPosition end) {
        for (int i = start.getLine(); i <= end.getLine(); i++) {
            float startX;
            float startY;
            float endX;
            float endY;

            if (i == start.getLine()) {
                startX = getCharOffsetX(i, start.getColumn());
                startY = getCharOffsetY(i, start.getColumn());
                int columns = getText().getColumnCount(i);
                endX = getCharOffsetX(i, columns);
                endY = getCharOffsetY(i, columns);
            } else if (i == end.getLine()) {
                startX = getCharOffsetX(i, 0);
                startY = getCharOffsetY(i, 0);
                endX = getCharOffsetX(i, end.getColumn());
                endY = getCharOffsetY(i, end.getColumn());
            } else {
                startX = getCharOffsetX(i, 0);
                startY = getCharOffsetY(i, 0);
                int columns = getText().getColumnCount(i);
                endX = getCharOffsetX(i, columns);
                endY = getCharOffsetY(i, columns);
            }

            drawSquigglyLine(canvas, startX, startY, endX, endY);
        }
    }

    private void drawSquigglyLine(Canvas canvas, float startX, float startY, float endX, float endY) {
        float waveSize = getDpUnit() * 3;
        float doubleWaveSize = waveSize * 2;
        float width = endX - startX;
        for (int i = (int) startX; i < startX + width; i += doubleWaveSize) {
            canvas.drawLine(i, startY, i + waveSize, startY - waveSize, mDiagnosticPaint);
            canvas.drawLine(i + waveSize, startY - waveSize, i + doubleWaveSize, startY, mDiagnosticPaint);
        }
    }

    private void setDiagnosticColor(DiagnosticWrapper wrapper) {
        EditorColorScheme color = getColorScheme();
        switch (wrapper.getKind()) {
            case ERROR:
                mDiagnosticPaint.setColor(color.getColor(EditorColorScheme.PROBLEM_ERROR));
                break;
            case MANDATORY_WARNING:
            case WARNING:
                mDiagnosticPaint.setColor(color.getColor(EditorColorScheme.PROBLEM_WARNING));
        }
    }
}
