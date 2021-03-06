/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.hadoop.yarn.server.nodemanager;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.ShutdownHookManager;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.YarnException;
import org.apache.hadoop.yarn.YarnUncaughtExceptionHandler;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.NodeHealthStatus;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.event.AsyncDispatcher;
import org.apache.hadoop.yarn.event.Dispatcher;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.ContainerManagerImpl;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.application.Application;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.Container;
import org.apache.hadoop.yarn.server.nodemanager.metrics.NodeManagerMetrics;
import org.apache.hadoop.yarn.server.nodemanager.webapp.WebServer;
import org.apache.hadoop.yarn.server.security.ApplicationACLsManager;
import org.apache.hadoop.yarn.server.security.ContainerTokenSecretManager;
import org.apache.hadoop.yarn.service.CompositeService;
import org.apache.hadoop.yarn.service.Service;
import org.apache.hadoop.yarn.service.ServiceStateChangeListener;
import org.apache.hadoop.yarn.util.Records;

public class NodeManager extends CompositeService implements
    ServiceStateChangeListener {

  /**
   * Priority of the NodeManager shutdown hook.
   */
  public static final int SHUTDOWN_HOOK_PRIORITY = 30;

  private static final Log LOG = LogFactory.getLog(NodeManager.class);
  protected final NodeManagerMetrics metrics = NodeManagerMetrics.create();
  protected ContainerTokenSecretManager containerTokenSecretManager;
  private ApplicationACLsManager aclsManager;
  private NodeHealthCheckerService nodeHealthChecker;
  private LocalDirsHandlerService dirsHandler;
  private static CompositeServiceShutdownHook nodeManagerShutdownHook; 
  
  public NodeManager() {
    super(NodeManager.class.getName());
  }

  protected NodeStatusUpdater createNodeStatusUpdater(Context context,
      Dispatcher dispatcher, NodeHealthCheckerService healthChecker,
      ContainerTokenSecretManager containerTokenSecretManager) {
    return new NodeStatusUpdaterImpl(context, dispatcher, healthChecker,
                                     metrics, containerTokenSecretManager);
  }

  protected NodeResourceMonitor createNodeResourceMonitor() {
    return new NodeResourceMonitorImpl();
  }

  protected ContainerManagerImpl createContainerManager(Context context,
      ContainerExecutor exec, DeletionService del,
      NodeStatusUpdater nodeStatusUpdater, ContainerTokenSecretManager 
      containerTokenSecretManager, ApplicationACLsManager aclsManager,
      LocalDirsHandlerService dirsHandler) {
    return new ContainerManagerImpl(context, exec, del, nodeStatusUpdater,
        metrics, containerTokenSecretManager, aclsManager, dirsHandler);
  }

  protected WebServer createWebServer(Context nmContext,
      ResourceView resourceView, ApplicationACLsManager aclsManager,
      LocalDirsHandlerService dirsHandler) {
    return new WebServer(nmContext, resourceView, aclsManager, dirsHandler);
  }

  protected void doSecureLogin() throws IOException {
    SecurityUtil.login(getConfig(), YarnConfiguration.NM_KEYTAB,
        YarnConfiguration.NM_PRINCIPAL);
  }

  @Override
  public void init(Configuration conf) {

    conf.setBoolean(Dispatcher.DISPATCHER_EXIT_ON_ERROR_KEY, true);

    Context context = new NMContext();

    // Create the secretManager if need be.
    if (UserGroupInformation.isSecurityEnabled()) {
      LOG.info("Security is enabled on NodeManager. "
          + "Creating ContainerTokenSecretManager");
      this.containerTokenSecretManager = new ContainerTokenSecretManager();
    }

    this.aclsManager = new ApplicationACLsManager(conf);

    ContainerExecutor exec = ReflectionUtils.newInstance(
        conf.getClass(YarnConfiguration.NM_CONTAINER_EXECUTOR,
          DefaultContainerExecutor.class, ContainerExecutor.class), conf);
    try {
      exec.init();
    } catch (IOException e) {
      throw new YarnException("Failed to initialize container executor", e);
    }    
    DeletionService del = new DeletionService(exec);
    addService(del);

    // NodeManager level dispatcher
    AsyncDispatcher dispatcher = new AsyncDispatcher();

    nodeHealthChecker = new NodeHealthCheckerService();
    addService(nodeHealthChecker);
    dirsHandler = nodeHealthChecker.getDiskHandler();

    NodeStatusUpdater nodeStatusUpdater = createNodeStatusUpdater(context,
        dispatcher, nodeHealthChecker, this.containerTokenSecretManager);
    nodeStatusUpdater.register(this);

    NodeResourceMonitor nodeResourceMonitor = createNodeResourceMonitor();
    addService(nodeResourceMonitor);

    ContainerManagerImpl containerManager =
        createContainerManager(context, exec, del, nodeStatusUpdater,
        this.containerTokenSecretManager, this.aclsManager, dirsHandler);
    addService(containerManager);

    Service webServer = createWebServer(context, containerManager
        .getContainersMonitor(), this.aclsManager, dirsHandler);
    addService(webServer);

    dispatcher.register(ContainerManagerEventType.class, containerManager);
    addService(dispatcher);

    DefaultMetricsSystem.initialize("NodeManager");

    // StatusUpdater should be added last so that it get started last 
    // so that we make sure everything is up before registering with RM. 
    addService(nodeStatusUpdater);

    super.init(conf);
    // TODO add local dirs to del
  }

  @Override
  public void start() {
    try {
      doSecureLogin();
    } catch (IOException e) {
      throw new YarnException("Failed NodeManager login", e);
    }
    super.start();
  }

  @Override
  public void stop() {
    super.stop();
    DefaultMetricsSystem.shutdown();
  }

  public static class NMContext implements Context {

    private final NodeId nodeId = Records.newRecord(NodeId.class);
    private final ConcurrentMap<ApplicationId, Application> applications =
        new ConcurrentHashMap<ApplicationId, Application>();
    private final ConcurrentMap<ContainerId, Container> containers =
        new ConcurrentSkipListMap<ContainerId, Container>();

    private final NodeHealthStatus nodeHealthStatus = RecordFactoryProvider
        .getRecordFactory(null).newRecordInstance(NodeHealthStatus.class);

    public NMContext() {
      this.nodeHealthStatus.setIsNodeHealthy(true);
      this.nodeHealthStatus.setHealthReport("Healthy");
      this.nodeHealthStatus.setLastHealthReportTime(System.currentTimeMillis());
    }

    /**
     * Usable only after ContainerManager is started.
     */
    @Override
    public NodeId getNodeId() {
      return this.nodeId;
    }

    @Override
    public ConcurrentMap<ApplicationId, Application> getApplications() {
      return this.applications;
    }

    @Override
    public ConcurrentMap<ContainerId, Container> getContainers() {
      return this.containers;
    }

    @Override
    public NodeHealthStatus getNodeHealthStatus() {
      return this.nodeHealthStatus;
    }
  }


  /**
   * @return the node health checker
   */
  public NodeHealthCheckerService getNodeHealthChecker() {
    return nodeHealthChecker;
  }

  @Override
  public void stateChanged(Service service) {
    if (NodeStatusUpdaterImpl.class.getName().equals(service.getName())
        && STATE.STOPPED.equals(service.getServiceState())) {

      boolean hasToReboot = ((NodeStatusUpdaterImpl) service).hasToRebootNode();

      // Shutdown the Nodemanager when the NodeStatusUpdater is stopped.      
      stop();

      // Reboot the whole node-manager if NodeStatusUpdater got a reboot command
      // from the RM.
      if (hasToReboot) {
        LOG.info("Rebooting the node manager.");
        NodeManager nodeManager = createNewNodeManager();
        nodeManager.initAndStartNodeManager(hasToReboot);
      }
    }
  }
  
  private void initAndStartNodeManager(boolean hasToReboot) {
    try {

      // Remove the old hook if we are rebooting.
      if (hasToReboot && null != nodeManagerShutdownHook) {
        ShutdownHookManager.get().removeShutdownHook(nodeManagerShutdownHook);
      }

      nodeManagerShutdownHook = new CompositeServiceShutdownHook(this);
      ShutdownHookManager.get().addShutdownHook(nodeManagerShutdownHook,
                                                SHUTDOWN_HOOK_PRIORITY);

      YarnConfiguration conf = new YarnConfiguration();
      this.init(conf);
      this.start();
    } catch (Throwable t) {
      LOG.fatal("Error starting NodeManager", t);
      System.exit(-1);
    }
  }

  // For testing
  NodeManager createNewNodeManager() {
    return new NodeManager();
  }

  public static void main(String[] args) {
    Thread.setDefaultUncaughtExceptionHandler(new YarnUncaughtExceptionHandler());
    StringUtils.startupShutdownMessage(NodeManager.class, args, LOG);
    NodeManager nodeManager = new NodeManager();
    nodeManager.initAndStartNodeManager(false);
  }
}
