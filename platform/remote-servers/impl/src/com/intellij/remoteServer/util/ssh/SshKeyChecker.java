/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.remoteServer.util.ssh;

import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.remoteServer.configuration.ServerConfiguration;
import com.intellij.remoteServer.runtime.ServerConnection;
import com.intellij.remoteServer.runtime.ServerConnectionManager;
import com.intellij.remoteServer.runtime.deployment.DeploymentLogManager;
import com.intellij.remoteServer.runtime.deployment.DeploymentTask;
import com.intellij.remoteServer.runtime.log.LoggingHandler;
import com.intellij.remoteServer.runtime.ui.RemoteServersView;
import com.intellij.remoteServer.util.*;
import com.intellij.util.ParameterizedRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.event.HyperlinkEvent;

/**
 * @author michael.golubev
 */
public class SshKeyChecker {

  private final boolean myTextNotFile;

  public SshKeyChecker(boolean textNotFile) {
    myTextNotFile = textNotFile;
  }

  private static boolean isSshKeyErrorMessage(String errorMessage) {
    return errorMessage.contains("Could not read from remote repository") || errorMessage.contains("The remote end hung up unexpectedly");
  }

  public void checkServerError(String errorMessage,
                               CloudNotifier notifier,
                               Project project,
                               CloudConnectionTask connectionTask) {
    if (isSshKeyErrorMessage(errorMessage)) {
      new ServerHandler(notifier, project, connectionTask).handle(errorMessage);
    }
    else {
      notifier.showMessage(errorMessage, MessageType.ERROR);
    }
  }

  public void checkDeploymentError(String errorMessage,
                                   SshKeyAwareServerRuntime serverRuntime,
                                   DeploymentLogManager logManager,
                                   DeploymentTask deploymentTask) {
    if (isSshKeyErrorMessage(errorMessage) && logManager != null) {
      new DeploymentHandler(serverRuntime, logManager.getMainLoggingHandler(), deploymentTask).handle();
    }
  }

  @Nullable
  private static ServerConnection findServerConnection(SshKeyAwareServerRuntime serverRuntime) {
    ServerConfiguration configuration = serverRuntime.getConfiguration();
    for (ServerConnection connection : ServerConnectionManager.getInstance().getConnections()) {
      if (connection.getServer().getConfiguration() == configuration) {
        return connection;
      }
    }
    return null;
  }

  private class ServerHandler extends HandlerBase {

    private final CloudNotifier myNotifier;
    private final Project myProject;
    private final CloudConnectionTask myConnectionTask;

    private Notification myErrorNotification;

    public ServerHandler(CloudNotifier notifier, Project project, CloudConnectionTask connectionTask) {
      myNotifier = notifier;
      myProject = project;
      myConnectionTask = connectionTask;
    }

    @Override
    public Project getProject() {
      return myProject;
    }

    public void handle(String errorMessage) {
      myNotifier.showMessage(errorMessage + "<br/>You may need to <a href=\"#\">upload SSH public key</a>",
                             MessageType.ERROR,
                             new NotificationListener() {

                               @Override
                               public void hyperlinkUpdate(@NotNull Notification notification, @NotNull HyperlinkEvent event) {
                                 myErrorNotification = notification;
                                 chooseKey();
                               }
                             });
    }

    @Override
    protected void uploadKey(final String sskKey) {
      new CloudConnectionTask(myProject, "Uploading SSH key", myConnectionTask.getServer()) {

        @Override
        protected Object run(CloudServerRuntimeInstance serverRuntime) throws ServerRuntimeException {
          ((SshKeyAwareServerRuntime)serverRuntime).addSshKey(sskKey);
          myErrorNotification.expire();
          myNotifier.showMessage("SSH key was uploaded, you may <a href=\"#\">reconnect</a>",
                                 MessageType.INFO,
                                 new NotificationListener() {

                                   @Override
                                   public void hyperlinkUpdate(@NotNull Notification notification, @NotNull HyperlinkEvent event) {
                                     notification.expire();
                                     reconnect();
                                   }
                                 });
          return null;
        }

        @Override
        protected void runtimeErrorOccurred(@NotNull String errorMessage) {
          myNotifier.showMessage(errorMessage, MessageType.ERROR);
        }
      }.performSync();
    }

    private void reconnect() {
      myConnectionTask.performAsync();
    }
  }

  private class DeploymentHandler extends HandlerBase {

    private final SshKeyAwareServerRuntime myServerRuntime;
    private final DeploymentTask myDeploymentTask;
    private final LoggingHandler myLoggingHandler;

    private DeploymentHandler(SshKeyAwareServerRuntime serverRuntime,
                              LoggingHandler loggingHandler,
                              DeploymentTask deploymentTask) {
      myServerRuntime = serverRuntime;
      myDeploymentTask = deploymentTask;
      myLoggingHandler = loggingHandler;
    }

    @Override
    protected Project getProject() {
      return myDeploymentTask.getProject();
    }

    public void handle() {
      myLoggingHandler.print("You may need to ");
      myLoggingHandler.printHyperlink("upload SSH public key", new HyperlinkInfo() {

        @Override
        public void navigate(Project project) {
          chooseKey();
        }
      });
      myLoggingHandler.print("\n");
    }

    @Override
    protected void uploadKey(final String sskKey) {
      new CloudRuntimeTask(getProject(), "Uploading SSH key") {

        @Override
        protected CloudServerRuntimeInstance getServerRuntime() {
          return myServerRuntime.asCloudServerRuntime();
        }

        @Override
        protected Object run(CloudServerRuntimeInstance serverRuntimeInstance) throws ServerRuntimeException {
          myServerRuntime.addSshKey(sskKey);

          myLoggingHandler.print("SSH key was uploaded, you may ");
          myLoggingHandler.printHyperlink("redeploy", new HyperlinkInfo() {


            @Override
            public void navigate(Project project) {
              redeploy();
            }
          });
          myLoggingHandler.print("\n");

          return null;
        }

        @Override
        protected void runtimeErrorOccurred(@NotNull String errorMessage) {
          myLoggingHandler.print("Unable to upload SSH key: " + errorMessage + "\n");
        }
      }.performSync();
    }

    private void redeploy() {
      final ServerConnection connection = findServerConnection(myServerRuntime);
      if (connection == null) {
        return;
      }

      final RemoteServersView view = RemoteServersView.getInstance(myDeploymentTask.getProject());
      view.showServerConnection(connection);

      connection.deploy(myDeploymentTask,
                        new ParameterizedRunnable<String>() {

                          @Override
                          public void run(String s) {
                            view.showDeployment(connection, s);
                          }
                        });
    }
  }

  private abstract class HandlerBase {

    protected void chooseKey() {
      new PublicSshKeyDialog(getProject(), myTextNotFile) {

        @Override
        protected void uploadSshKey(String sskKey) {
          uploadKey(sskKey);
        }
      }.show();
    }

    protected abstract void uploadKey(String sskKey);

    protected abstract Project getProject();
  }
}
