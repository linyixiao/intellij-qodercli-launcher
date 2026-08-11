package dev.linzhang.qodercli;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The plugin's one way of telling the user that something did not work.
 *
 * <p>Everything this plugin does is a side effect in a terminal, so a failure that is swallowed
 * looks exactly like a menu item that does nothing. A balloon is the least intrusive way to say
 * "your click was received, but here is why nothing happened" — it never steals focus and it
 * survives in the Notifications tool window.
 *
 * <p>The group id is declared in {@code plugin.xml}.
 */
final class QoderCliNotifications {

    private static final String GROUP_ID = "Qoder CLI Launcher";

    private QoderCliNotifications() {
    }

    /** Show a warning balloon for {@code project}. Safe to call from any thread. */
    static void warn(@NotNull Project project, @NotNull String title, @Nullable String details) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(GROUP_ID)
                    .createNotification(title, details == null ? "" : details, NotificationType.WARNING)
                    .notify(project);
        });
    }
}
