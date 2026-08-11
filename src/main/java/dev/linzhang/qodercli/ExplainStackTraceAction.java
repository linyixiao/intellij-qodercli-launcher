package dev.linzhang.qodercli;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Run / Debug console action: hand the selected output — typically an exception stack trace — to
 * the Qoder CLI and ask for an explanation and a fix.
 *
 * <p>With no selection the tail of the console is used, which is almost always the failure that
 * just happened. The text is written to a temporary file and attached, both because stack traces
 * are long and because the prompt itself must stay on one line (a newline typed into a running
 * qodercli session would submit it prematurely).
 *
 * <p>As a small assist we scan the text for {@code Foo.java:42}-style references and name them in
 * the prompt, so the agent starts from the right files instead of searching the whole repository.
 */
public final class ExplainStackTraceAction extends AnAction implements DumbAware {

    /** How much of the console to take when the user has not selected anything. */
    private static final int TAIL_LINES = 120;

    /** Cap on how many {@code File.ext:line} references we quote back in the prompt. */
    private static final int MAX_REFS = 8;

    private static final Pattern SOURCE_REF = Pattern.compile(
            "([A-Za-z_$][A-Za-z0-9_$]*\\.(?:java|kt|kts|scala|groovy|py|go|rs|rb|php|cs|swift|m|mm|c|cc|cpp|h|hpp|ts|tsx|js|jsx|vue)):(\\d+)");

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        // We look at the console editor, which must be read on the EDT.
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        boolean hasText = editor != null && editor.getDocument().getTextLength() > 0;
        e.getPresentation().setEnabledAndVisible(project != null && hasText);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) {
            return;
        }

        String text = textToExplain(editor);
        if (text == null || text.isBlank()) {
            return;
        }

        String workingDir = project.getBasePath();
        if (workingDir == null) {
            return;
        }

        final String finalText = text;
        final String finalWorkingDir = workingDir;

        // Writing the temp file is disk IO, so do it off the EDT and hop back to touch the terminal.
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            File dump = writeTempDump(finalText);
            if (dump == null) {
                QoderCliNotifications.warn(project, "无法写入报错内容的临时文件",
                        "请检查系统临时目录是否可写，然后重试。");
                return;
            }
            String path = dump.getAbsolutePath();
            String prompt = buildPrompt(finalText);

            List<String> args = new ArrayList<>();
            args.add("--attachment");
            args.add(path);
            args.add("-i");
            args.add(prompt);

            String injectMessage = QoderCliLauncher.singleLine(
                    prompt + " （报错内容在这个文件里: " + path + "）");

            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) {
                    QoderCliLauncher.dispatch(project, finalWorkingDir, args, injectMessage);
                }
            });
        });
    }

    /** The selected console text, or the tail of the console when nothing is selected. */
    private static @Nullable String textToExplain(@NotNull Editor editor) {
        String selected = editor.getSelectionModel().getSelectedText();
        if (selected != null && !selected.isBlank()) {
            return selected;
        }
        Document document = editor.getDocument();
        int lineCount = document.getLineCount();
        if (lineCount == 0) {
            return null;
        }
        int firstLine = Math.max(0, lineCount - TAIL_LINES);
        int start = document.getLineStartOffset(firstLine);
        // Read just the tail: a long-running console can hold megabytes, and document.getText()
        // would copy all of it only for us to discard everything before `start`.
        return document.getText(new TextRange(start, document.getTextLength()));
    }

    /**
     * A single-line prompt, naming the source files the trace points at so the agent does not have
     * to search for them.
     */
    private static @NotNull String buildPrompt(@NotNull String text) {
        String prompt = "\u8bf7\u89e3\u91ca\u9644\u4ef6\u91cc\u7684\u62a5\u9519\uff0c\u5b9a\u4f4d\u6839\u56e0\u5e76\u7ed9\u51fa\u4fee\u590d\u65b9\u6848\u3002";
        Set<String> refs = sourceReferences(text);
        if (!refs.isEmpty()) {
            prompt += "\u6d89\u53ca\uff1a" + String.join("\u3001", refs) + "\u3002";
        }
        return prompt;
    }

    /** Distinct {@code Foo.java:42} references found in the text, in order of appearance. */
    private static @NotNull Set<String> sourceReferences(@NotNull String text) {
        Set<String> refs = new LinkedHashSet<>();
        Matcher matcher = SOURCE_REF.matcher(text);
        while (matcher.find() && refs.size() < MAX_REFS) {
            refs.add(matcher.group(1) + ":" + matcher.group(2));
        }
        return refs;
    }

    /** Write the console excerpt to a temp file so it can be attached. */
    private static @Nullable File writeTempDump(@NotNull String content) {
        try {
            File tmp = File.createTempFile("qoder-stacktrace-", ".txt");
            tmp.deleteOnExit();
            Files.writeString(tmp.toPath(), content, StandardCharsets.UTF_8);
            return tmp;
        } catch (IOException ex) {
            return null;
        }
    }
}
