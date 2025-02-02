package com.tyron.code.ui.editor.impl.text.rosemoe;


import static io.github.rosemoe.sora2.text.EditorUtil.setDefaultColorScheme;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.ForwardingListener;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentResultListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.tyron.actions.ActionManager;
import com.tyron.actions.ActionPlaces;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.actions.util.DataContextUtils;
import com.tyron.builder.compiler.manifest.xml.XmlFormatPreferences;
import com.tyron.builder.compiler.manifest.xml.XmlFormatStyle;
import com.tyron.builder.compiler.manifest.xml.XmlPrettyPrinter;
import com.tyron.builder.log.LogViewModel;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.listener.FileListener;
import com.tyron.code.R;
import com.tyron.code.ui.editor.CodeAssistCompletionAdapter;
import com.tyron.code.ui.editor.CodeAssistCompletionLayout;
import com.tyron.code.ui.editor.EditorViewModel;
import com.tyron.code.ui.editor.Savable;
import com.tyron.code.ui.editor.impl.FileEditorManagerImpl;
import com.tyron.code.ui.editor.language.LanguageManager;
import com.tyron.code.ui.editor.language.java.JavaLanguage;
import com.tyron.code.ui.editor.language.xml.LanguageXML;
import com.tyron.code.ui.editor.shortcuts.ShortcutAction;
import com.tyron.code.ui.editor.shortcuts.ShortcutItem;
import com.tyron.code.ui.layoutEditor.LayoutEditorFragment;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.code.ui.settings.EditorSettingsFragment;
import com.tyron.code.util.CoordinatePopupMenu;
import com.tyron.code.util.PopupMenuHelper;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.common.util.AndroidUtilities;
import com.tyron.completion.java.provider.CompletionEngine;
import com.tyron.completion.java.util.DiagnosticUtil;
import com.tyron.completion.java.util.JavaDataContextUtil;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.editor.CharPosition;
import com.tyron.editor.Editor;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import io.github.rosemoe.sora.event.ClickEvent;
import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.LongPressEvent;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.langs.textmate.theme.TextMateColorScheme;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.widget.DirectAccessProps;
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion;
import io.github.rosemoe.sora2.text.EditorUtil;

@SuppressWarnings("FieldCanBeLocal")
public class CodeEditorFragment extends Fragment implements Savable,
        SharedPreferences.OnSharedPreferenceChangeListener, FileListener,
        ProjectManager.OnProjectOpenListener {

    public static final String KEY_LINE = "line";
    public static final String KEY_COLUMN = "column";

    public static CodeEditorFragment newInstance(File file) {
        CodeEditorFragment fragment = new CodeEditorFragment();
        Bundle args = new Bundle();
        args.putString("path", file.getAbsolutePath());
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * Creates a new instance of the editor with the the cursor positioned at the given
     * line and column
     *
     * @param file   The file to be r ead
     * @param line   The 0-based line
     * @param column The 0-based column
     * @return The editor instance
     */
    public static CodeEditorFragment newInstance(File file, int line, int column) {
        CodeEditorFragment fragment = new CodeEditorFragment();
        Bundle args = new Bundle();
        args.putInt(KEY_LINE, line);
        args.putInt(KEY_COLUMN, column);
        fragment.setArguments(args);
        return fragment;
    }

    private static final String NO_PROJECT_OPEN_STRING =
            "Cannot read file: No project is currently opened. The changes will not be saved.";

    /**
     * Keys for saved states
     */
    private static final String EDITOR_LEFT_LINE_KEY = "line";
    private static final String EDITOR_LEFT_COLUMN_KEY = "column";
    private static final String EDITOR_RIGHT_LINE_KEY = "rightLine";
    private static final String EDITOR_RIGHT_COLUMN_KEY = "rightColumn";

    private CodeEditorView mEditor;

    private Language mLanguage;
    private File mCurrentFile = new File("");
    private MainViewModel mMainViewModel;
    private SharedPreferences mPreferences;

    private Bundle mSavedInstanceState;

    private boolean mCanSave;
    private boolean mReading;

    private View.OnTouchListener mDragToOpenListener;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mCurrentFile = new File(requireArguments().getString("path", ""));
        mMainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        mSavedInstanceState = savedInstanceState;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        Cursor cursor = mEditor.getCursor();
        outState.putInt(EDITOR_LEFT_LINE_KEY, cursor.getLeftLine());
        outState.putInt(EDITOR_LEFT_COLUMN_KEY, cursor.getLeftColumn());
        outState.putInt(EDITOR_RIGHT_LINE_KEY, cursor.getRightLine());
        outState.putInt(EDITOR_RIGHT_COLUMN_KEY, cursor.getRightColumn());
    }

    @Override
    public void onResume() {
        super.onResume();

        if (BottomSheetBehavior.STATE_HIDDEN ==
            mMainViewModel.getBottomSheetState()
                    .getValue()) {
            mMainViewModel.setBottomSheetState(BottomSheetBehavior.STATE_COLLAPSED);
        }
    }


    public void hideEditorWindows() {
        mEditor.hideAutoCompleteWindow();
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
        mPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext());

        View root = inflater.inflate(R.layout.code_editor_fragment, container, false);

        mEditor = root.findViewById(R.id.code_editor);
        mEditor.setBackgroundAnalysisEnabled(false);
        configure(mEditor.getProps());
        mEditor.setTextSize(
                Integer.parseInt(mPreferences.getString(SharedPreferenceKeys.FONT_SIZE, "12")));
        mEditor.openFile(mCurrentFile);
        mEditor.getComponent(EditorAutoCompletion.class)
                .setLayout(new CodeAssistCompletionLayout());
        mEditor.setAutoCompletionItemAdapter(new CodeAssistCompletionAdapter());
        mEditor.setTypefaceText(
                ResourcesCompat.getFont(requireContext(), R.font.jetbrains_mono_regular));
        mEditor.setLigatureEnabled(true);
        mEditor.setHighlightCurrentBlock(true);
        mEditor.setEdgeEffectColor(Color.TRANSPARENT);
        mEditor.setWordwrap(mPreferences.getBoolean(SharedPreferenceKeys.EDITOR_WORDWRAP, false));
        mEditor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);

        View topView = root.findViewById(R.id.top_view);
        EditorViewModel viewModel = new ViewModelProvider(this).get(EditorViewModel.class);
        viewModel.getAnalyzeState()
                .observe(getViewLifecycleOwner(), analyzing -> {
                    if (analyzing) {
                        topView.setVisibility(View.VISIBLE);
                    } else {
                        topView.setVisibility(View.GONE);
                    }
                });
        mEditor.setViewModel(viewModel);
        mEditor.setInputType(EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS |
                             EditorInfo.TYPE_CLASS_TEXT |
                             EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE |
                             EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        mPreferences.registerOnSharedPreferenceChangeListener(this);
        String schemePath = mPreferences.getString("scheme", null);
        setScheme(schemePath);
        return root;
    }

    private void initializeLanguage() {
        mLanguage = LanguageManager.getInstance()
                .get(mEditor, mCurrentFile);
        mEditor.setEditorLanguage(mLanguage);
    }

    private void setScheme(@Nullable String path) {
        if (path != null && new File(path).exists()) {
            ListenableFuture<TextMateColorScheme> future =
                    EditorSettingsFragment.getColorScheme(new File(path));
            Futures.addCallback(future, new FutureCallback<TextMateColorScheme>() {
                @Override
                public void onSuccess(@Nullable TextMateColorScheme result) {
                    if (result == null) {
                        setDefaultColorScheme(mEditor);
                    } else {
                        mEditor.setColorScheme(result);
                    }
                    initializeLanguage();
                    ProjectManager.getInstance()
                            .addOnProjectOpenListener(CodeEditorFragment.this);
                }

                @Override
                public void onFailure(@NonNull Throwable t) {
                    setDefaultColorScheme(mEditor);
                    initializeLanguage();
                    ProjectManager.getInstance()
                            .addOnProjectOpenListener(CodeEditorFragment.this);
                }
            }, ContextCompat.getMainExecutor(requireContext()));
        } else {
            setDefaultColorScheme(mEditor);
            initializeLanguage();
            ProjectManager.getInstance()
                    .addOnProjectOpenListener(this);
        }
    }

    private void configure(DirectAccessProps props) {
        props.overScrollEnabled = false;
        props.allowFullscreen = false;
        props.deleteEmptyLineFast =
                mPreferences.getBoolean(SharedPreferenceKeys.DELETE_WHITESPACES, false);
    }


    @Override
    public void onSharedPreferenceChanged(SharedPreferences pref, String key) {
        if (mEditor == null) {
            return;
        }
        switch (key) {
            case SharedPreferenceKeys.FONT_SIZE:
                mEditor.setTextSize(Integer.parseInt(pref.getString(key, "14")));
                break;
            case SharedPreferenceKeys.EDITOR_WORDWRAP:
                mEditor.setWordwrap(pref.getBoolean(key, false));
                break;
            case SharedPreferenceKeys.DELETE_WHITESPACES:
                mEditor.getProps().deleteEmptyLineFast =
                        pref.getBoolean(SharedPreferenceKeys.DELETE_WHITESPACES, false);
                break;
            case SharedPreferenceKeys.SCHEME:
                setScheme(pref.getString(SharedPreferenceKeys.SCHEME, null));
                break;
        }
    }

    @SuppressLint("RestrictedApi")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mEditor.setOnCreateContextMenuListener((menu, view1, contextMenuInfo) -> {
            menu.clear();
        });
        // noinspection ClickableViewAccessibility
        mEditor.setOnTouchListener((view12, motionEvent) -> {
            if (mDragToOpenListener instanceof ForwardingListener) {
                PopupMenuHelper.setForwarding((ForwardingListener) mDragToOpenListener);
                // noinspection RestrictedApi
                ((ForwardingListener) mDragToOpenListener).onTouch(view12, motionEvent);
            }
            return false;
        });
        mEditor.subscribeEvent(LongPressEvent.class, (event, unsubscribe) -> {
            updateFile(mEditor.getText());
            // wait for the cursor to move
            ProgressManager.getInstance()
                    .runLater(() -> {
                        showPopupMenu(event);
                    });
        });
        mEditor.subscribeEvent(ClickEvent.class, (event, unsubscribe) -> {
            Cursor cursor = mEditor.getCursor();
            if (mEditor.getCursor()
                    .isSelected()) {
                int index = mEditor.getCharIndex(event.getLine(), event.getColumn());
                int cursorLeft = cursor.getLeft();
                int cursorRight = cursor.getRight();
                if (index >= cursorLeft && index <= cursorRight) {
                    mEditor.showSoftInput();
                    event.intercept();
                }
            }
        });
        mEditor.subscribeEvent(ContentChangeEvent.class, (event, unsubscribe) -> {
            if (event.getAction() == ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                return;
            }
            updateFile(event.getEditor()
                               .getText());
        });

        LogViewModel logViewModel =
                new ViewModelProvider(requireActivity()).get(LogViewModel.class);
        mEditor.setDiagnosticsListener(diagnostics -> {
            for (DiagnosticWrapper diagnostic : diagnostics) {
                DiagnosticUtil.setLineAndColumn(diagnostic, mEditor);
            }
            ProgressManager.getInstance()
                    .runLater(() -> logViewModel.updateLogs(LogViewModel.DEBUG, diagnostics));
        });
        FragmentManager fragmentManager = getChildFragmentManager();
        FragmentResultListener listener = ((requestKey, result) -> {
            String xml = result.getString("text", mEditor.getText()
                    .toString());
            xml = XmlPrettyPrinter.prettyPrint(xml, XmlFormatPreferences.defaults(),
                                               XmlFormatStyle.LAYOUT, "\n");
            mEditor.setText(xml);
        });
        fragmentManager.setFragmentResultListener(LayoutEditorFragment.KEY_SAVE,
                                                  getViewLifecycleOwner(), listener);
    }

    private void showPopupMenu(LongPressEvent event) {
        MotionEvent e = event.getCausingEvent();
        Cursor cursor = mEditor.getCursor();
        if (cursor.isSelected()) {
            int index = mEditor.getCharIndex(event.getLine(), event.getColumn());
            int cursorLeft = cursor.getLeft();
            int cursorRight = cursor.getRight();
            char c = mEditor.getText()
                    .charAt(index);
            if (Character.isWhitespace(c)) {
                mEditor.setSelection(event.getLine(), event.getColumn());
            } else if (index < cursorLeft || index > cursorRight) {
                EditorUtil.selectWord(mEditor, event.getLine(), event.getColumn());
            }
        }
        CoordinatePopupMenu popupMenu = new CoordinatePopupMenu(requireContext(), mEditor,
                                                                Gravity.BOTTOM);
        DataContext dataContext = createDataContext();
        ActionManager.getInstance()
                .fillMenu(dataContext, popupMenu.getMenu(), ActionPlaces.EDITOR, true, false);
        popupMenu.show((int) e.getX(), ((int) e.getY()) - AndroidUtilities.dp(24));

        ProgressManager.getInstance().runLater(() -> {
            popupMenu.setOnDismissListener(d -> mDragToOpenListener = null);
            mDragToOpenListener = popupMenu.getDragToOpenListener();
        }, 300);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mEditor.setEditorLanguage(null);

        Project currentProject = ProjectManager.getInstance()
                .getCurrentProject();
        if (currentProject != null) {
            Module module = currentProject.getModule(mCurrentFile);
            if (module != null) {
                module.getFileManager()
                        .removeSnapshotListener(this);
            }
        }

        ProjectManager.getInstance()
                .removeOnProjectOpenListener(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (ProjectManager.getInstance()
                    .getCurrentProject() != null && mCanSave) {
            ProgressManager.getInstance()
                    .runNonCancelableAsync(() -> ProjectManager.getInstance()
                            .getCurrentProject()
                            .getModule(mCurrentFile)
                            .getFileManager()
                            .closeFileForSnapshot(mCurrentFile));
        }
        mPreferences.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();

        hideEditorWindows();

        if (mCanSave && !mReading) {
            if (ProjectManager.getInstance()
                        .getCurrentProject() != null) {
                ProjectManager.getInstance()
                        .getCurrentProject()
                        .getModule(mCurrentFile)
                        .getFileManager()
                        .setSnapshotContent(mCurrentFile, mEditor.getText()
                                .toString(), false);
            } else {
                ProgressManager.getInstance()
                        .runNonCancelableAsync(() -> {
                            try {
                                FileUtils.writeStringToFile(mCurrentFile, mEditor.getText()
                                        .toString(), StandardCharsets.UTF_8);
                            } catch (IOException e) {
                                // ignored
                            }
                        });
            }
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();

        mEditor.setBackgroundAnalysisEnabled(false);
    }

    @Override
    public void onSnapshotChanged(File file, CharSequence contents) {
        if (mCurrentFile.equals(file)) {
            if (mEditor != null) {
                if (!mEditor.getText()
                        .toString()
                        .contentEquals(contents)) {
                    Cursor cursor = mEditor.getCursor();
                    int left = cursor.getLeft();
                    mEditor.setText(contents);

                    if (left > contents.length()) {
                        left = contents.length();
                    }
                    CharPosition position = mEditor.getCharPosition(left);
                    mEditor.setSelection(position.getLine(), position.getColumn());
                }
            }
        }
    }

    @Override
    public void save(boolean toDisk) {
        if (!mCanSave || mReading) {
            return;
        }

        if (!mCurrentFile.exists()) {
            return;
        }

        if (ProjectManager.getInstance()
                    .getCurrentProject() != null && !toDisk) {
            ProjectManager.getInstance()
                    .getCurrentProject()
                    .getModule(mCurrentFile)
                    .getFileManager()
                    .setSnapshotContent(mCurrentFile, mEditor.getText()
                            .toString(), false);
        } else {
            ProgressManager.getInstance()
                    .runNonCancelableAsync(() -> {
                        try {
                            FileUtils.writeStringToFile(mCurrentFile, mEditor.getText()
                                    .toString(), StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            // ignored
                        }
                    });
        }
    }

    @Override
    public void onProjectOpen(Project project) {
        readFile(project, mSavedInstanceState);
    }

    private ListenableFuture<String> readFile() {
        return ProgressManager.getInstance()
                .computeNonCancelableAsync(() -> Futures.immediateFuture(
                        FileUtils.readFileToString(mCurrentFile, StandardCharsets.UTF_8)));
    }

    private void readFile(@NonNull Project currentProject, @Nullable Bundle savedInstanceState) {
        Module module = currentProject.getModule(mCurrentFile);
        FileManager fileManager = module.getFileManager();
        fileManager.addSnapshotListener(this);

        if (fileManager.isOpened(mCurrentFile)) {
            Optional<CharSequence> contents = fileManager.getFileContent(mCurrentFile);
            if (contents.isPresent()) {
                mEditor.setText(contents.get());
                return;
            }
        }

        mReading = true;
        mEditor.setBackgroundAnalysisEnabled(false);
        ListenableFuture<String> future = readFile();
        Futures.addCallback(future, new FutureCallback<String>() {
            @Override
            public void onSuccess(@Nullable String result) {
                mReading = false;
                if (getContext() == null) {
                    mCanSave = false;
                    return;
                }
                mCanSave = true;
                mEditor.setBackgroundAnalysisEnabled(true);
                fileManager.openFileForSnapshot(mCurrentFile, result);

                Bundle bundle = new Bundle();
                bundle.putBoolean("loaded", true);
                bundle.putBoolean("bg", true);
                mEditor.setText(result, bundle);
                if (savedInstanceState != null) {
                    restoreState(savedInstanceState);
                } else {
                    int line = requireArguments().getInt(KEY_LINE, 0);
                    int column = requireArguments().getInt(KEY_COLUMN, 0);
                    Content text = mEditor.getText();
                    if (line < text.getLineCount() && column < text.getColumnCount(line)) {
                        setCursorPosition(line, column);
                    }
                }

                checkCanSave();
            }

            @Override
            public void onFailure(Throwable t) {
                mCanSave = false;
                mReading = false;
                if (getContext() != null) {
                    checkCanSave();
                }
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void checkCanSave() {
        if (!mCanSave) {
            Snackbar snackbar =
                    Snackbar.make(mEditor, R.string.editor_error_file, Snackbar.LENGTH_INDEFINITE)
                            .setAction(R.string.menu_close, v -> FileEditorManagerImpl.getInstance()
                                    .closeFile(mCurrentFile));
            ViewGroup snackbarView = (ViewGroup) snackbar.getView();
            AndroidUtilities.setMargins(snackbarView, 0, 0, 0, 50);
            snackbar.show();
        }
    }

    private void showFileConflict() {

    }

    private void restoreState(@NonNull Bundle savedInstanceState) {
        int leftLine = savedInstanceState.getInt(EDITOR_LEFT_LINE_KEY, 0);
        int leftColumn = savedInstanceState.getInt(EDITOR_LEFT_COLUMN_KEY, 0);
        int rightLine = savedInstanceState.getInt(EDITOR_RIGHT_LINE_KEY, 0);
        int rightColumn = savedInstanceState.getInt(EDITOR_RIGHT_COLUMN_KEY, 0);

        Content text = mEditor.getText();
        if (leftLine > text.getLineCount() || rightLine > text.getLineCount()) {
            return;
        }
        if (leftLine != rightLine && leftColumn != rightColumn) {
            mEditor.setSelectionRegion(leftLine, leftColumn, rightLine, rightColumn, true);
        } else {
            mEditor.setSelection(leftLine, leftColumn);
        }
    }

    private void updateFile(CharSequence contents) {
        Project project = ProjectManager.getInstance()
                .getCurrentProject();
        if (project == null) {
            return;
        }
        Module module = project.getModule(mCurrentFile);
        if (module != null) {
            module.getFileManager()
                    .setSnapshotContent(mCurrentFile, contents.toString(), this);
        }
    }

    public Editor getEditor() {
        return mEditor;
    }

    /**
     * Undo the text in the editor if possible, if not the call is ignored
     */
    public void undo() {
        if (mEditor == null) {
            return;
        }
        if (mEditor.canUndo()) {
            mEditor.undo();
        }
    }

    /**
     * Redo the text in the editor if possible, if not the call is ignored
     */
    public void redo() {
        if (mEditor == null) {
            return;
        }
        if (mEditor.canRedo()) {
            mEditor.redo();
        }
    }

    /**
     * Sets the position of the cursor in the editor
     *
     * @param line   zero-based line.
     * @param column zero-based column.
     */
    public void setCursorPosition(int line, int column) {
        if (mEditor != null) {
            mEditor.getCursor()
                    .set(line, column);
        }
    }

    /**
     * Perform a shortcut item to the editor
     *
     * @param item the item to be performed
     */
    public void performShortcut(ShortcutItem item) {
        if (mEditor == null) {
            return;
        }
        for (ShortcutAction action : item.actions) {
            action.apply(mEditor, item);
        }
    }

    public void format() {
        if (mEditor != null) {
//            if (mEditor.getCursor().isSelected()) {
//                if (mLanguage instanceof JavaLanguage) {
//                    Cursor cursor = mEditor.getCursor();
//                    CharSequence format = mLanguage.format(mEditor.getText(), cursor.getLeft(),
//                            cursor.getRight());
//                    mEditor.setText(format);
//                    return;
//                }
//            }
            mEditor.formatCodeAsync();
        }
    }

    /**
     * Notifies the editor to analyze and highlight the current text
     */
    public void analyze() {
        if (mEditor != null && !mReading) {
            mEditor.rerunAnalysis();
        }
    }

    private DataContext createDataContext() {
        Project currentProject = ProjectManager.getInstance()
                .getCurrentProject();

        DataContext dataContext = DataContextUtils.getDataContext(mEditor);
        dataContext.putData(CommonDataKeys.PROJECT, currentProject);
        dataContext.putData(CommonDataKeys.ACTIVITY, requireActivity());
        dataContext.putData(CommonDataKeys.FILE_EDITOR_KEY, mMainViewModel.getCurrentFileEditor());
        dataContext.putData(CommonDataKeys.FILE, mCurrentFile);
        dataContext.putData(CommonDataKeys.EDITOR, mEditor);

        if (currentProject != null && mLanguage instanceof JavaLanguage) {
            JavaDataContextUtil.addEditorKeys(dataContext, currentProject, mCurrentFile,
                                              mEditor.getCursor()
                                                      .getLeft());
        }

        DiagnosticWrapper diagnosticWrapper =
                DiagnosticUtil.getDiagnosticWrapper(mEditor.getDiagnostics(), mEditor.getCursor()
                        .getLeft());
        if (diagnosticWrapper == null && mLanguage instanceof LanguageXML) {
            diagnosticWrapper = DiagnosticUtil.getXmlDiagnosticWrapper(mEditor.getDiagnostics(),
                                                                       mEditor.getCursor()
                                                                               .getLeftLine());
        }
        dataContext.putData(CommonDataKeys.DIAGNOSTIC, diagnosticWrapper);
        return dataContext;
    }
}
