// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.ha;

import static org.apache.cloudstack.framework.config.ConfigKey.Scope.Zone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.managed.context.ManagedContext;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.management.ManagementServerHost;
import org.apache.logging.log4j.ThreadContext;

import com.cloud.agent.AgentManager;
import com.cloud.alert.AlertManager;
import com.cloud.cluster.ClusterManagerListener;
import com.cloud.consoleproxy.ConsoleProxyManager;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.deploy.HAPlanner;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.ha.Investigator.UnknownVM;
import com.cloud.ha.dao.HighAvailabilityDao;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.VpcVirtualNetworkApplianceService;
import com.cloud.resource.ResourceManager;
import com.cloud.server.ManagementServer;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StorageManager;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.secondary.SecondaryStorageVmManager;
import com.cloud.user.AccountManager;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * HighAvailabilityManagerImpl coordinates the HA process. VMs are registered with the HA Manager for HA. The request is stored
 * within a database backed work queue. HAManager has a number of workers that pick up these work items to perform HA on the
 * VMs.
 *
 * The HA process goes as follows: 1. Check with the list of Investigators to determine that the VM is no longer running. If a
 * Investigator finds the VM is still alive, the HA process is stopped and the state of the VM reverts back to its previous
 * state. If a Investigator finds the VM is dead, then HA process is started on the VM, skipping step 2. 2. If the list of
 * Investigators can not determine if the VM is dead or alive. The list of FenceBuilders is invoked to fence off the VM so that
 * it won't do any damage to the storage and network. 3. The VM is marked as stopped. 4. The VM is started again via the normal
 * process of starting VMs. Note that once the VM is marked as stopped, the user may have started the VM explicitly. 5. VMs that
 * have re-started more than the configured number of times are marked as in Error state and the user is not allowed to restart
 * the VM.
 *
 * @config {@table || Param Name | Description | Values | Default || || workers | number of worker threads to spin off to do the
 *         processing | int | 1 || || time.to.sleep | Time to sleep if no work items are found | seconds | 60 || || max.retries
 *         | number of times to retry start | int | 5 || || time.between.failure | Time elapsed between failures before we
 *         consider it as another retry | seconds | 3600 || || time.between.cleanup | Time to wait before the cleanup thread
 *         runs | seconds | 86400 || || force.ha | Force HA to happen even if the VM says no | boolean | false || ||
 *         ha.retry.wait | time to wait before retrying the work item | seconds | 120 || || stop.retry.wait | time to wait
 *         before retrying the stop | seconds | 120 || * }
 **/
public class HighAvailabilityManagerImpl extends ManagerBase implements Configurable, HighAvailabilityManager, ClusterManagerListener {

    private static final int SECONDS_TO_MILLISECONDS_FACTOR = 1000;

    private ConfigKey<Integer> MigrationMaxRetries = new ConfigKey<>("Advanced", Integer.class,
            "vm.ha.migration.max.retries","5",
            "Total number of attempts for trying migration of a VM.",
            true, ConfigKey.Scope.Global);

    public static ConfigKey<Boolean> VmHaEnabled = new ConfigKey<>("Advanced", Boolean.class, "vm.ha.enabled", "true",
            "Enable/Disable VM High Availability manager, it is enabled by default."
                    + " When enabled, the VM HA WorkItems (for VM Stop, Restart, Migration, Destroy) can be created and the scheduled items are executed; and"
                    + " When disabled, new VM HA WorkItems are not allowed and the scheduled items are retried until max retries configured at 'vm.ha.migration.max.retries'"
                    + " (executed in case HA is re-enabled during retry attempts), and then purged after 'time.between.failures' by the cleanup thread that runs"
                    + " regularly at 'time.between.cleanup'", true, Zone);

    protected static ConfigKey<Boolean> VmHaAlertsEnabled = new ConfigKey<>("Advanced", Boolean.class, "vm.ha.alerts.enabled", "true",
            "Enable/Disable alerts for the VM HA operations, it is enabled by default.", true, Zone);

    protected static final List<ReasonType> CancellableWorkReasonTypes =
            Arrays.asList(ReasonType.HostMaintenance, ReasonType.HostDown, ReasonType.HostDegraded);

    WorkerThread[] _workers;
    boolean _stopped;
    long _timeToSleep;
    @Inject
    HighAvailabilityDao _haDao;
    @Inject
    VMInstanceDao _instanceDao;
    @Inject
    HostDao _hostDao;
    @Inject
    DataCenterDao _dcDao;
    @Inject
    HostPodDao _podDao;
    @Inject
    ClusterDetailsDao _clusterDetailsDao;
    @Inject
    ServiceOfferingDao _serviceOfferingDao;
    @Inject
    private ConsoleProxyManager consoleProxyManager;
    @Inject
    private SecondaryStorageVmManager secondaryStorageVmManager;
    @Inject
    VolumeDao volumeDao;
    @Inject
    DataStoreProviderManager dataStoreProviderMgr;
    @Inject
    VpcVirtualNetworkApplianceService routerService;
    @Inject
    UserVmManager userVmManager;

    long _serverId;

    @Inject
    ManagedContext _managedContext;

    List<Investigator> investigators;

    public List<Investigator> getInvestigators() {
        return investigators;
    }

    public void setInvestigators(List<Investigator> investigators) {
        this.investigators = investigators;
    }

    List<FenceBuilder> fenceBuilders;

    public List<FenceBuilder> getFenceBuilders() {
        return fenceBuilders;
    }

    public void setFenceBuilders(List<FenceBuilder> fenceBuilders) {
        this.fenceBuilders = fenceBuilders;
    }

    List<HAPlanner> _haPlanners;
    public List<HAPlanner> getHaPlanners() {
        return _haPlanners;
    }

    public void setHaPlanners(List<HAPlanner> haPlanners) {
        _haPlanners = haPlanners;
    }

    @Inject
    AgentManager _agentMgr;
    @Inject
    AlertManager _alertMgr;
    @Inject
    StorageManager _storageMgr;
    @Inject
    GuestOSDao _guestOSDao;
    @Inject
    GuestOSCategoryDao _guestOSCategoryDao;
    @Inject
    VirtualMachineManager _itMgr;
    @Inject
    AccountManager _accountMgr;
    @Inject
    ResourceManager _resourceMgr;
    @Inject
    ManagementServer _msServer;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    VolumeOrchestrationService volumeMgr;

    String _instance;
    ScheduledExecutorService _executor;
    int _stopRetryInterval;
    int _investigateRetryInterval;
    int _migrateRetryInterval;
    int _restartRetryInterval;

    int _maxRetries;
    long _timeBetweenFailures;
    long _timeBetweenCleanups;
    String _haTag = null;

    protected HighAvailabilityManagerImpl() {
    }

    @Override
    public Status investigate(final long hostId) {
        final HostVO host = _hostDao.findById(hostId);
        if (host == null) {
            return Status.Alert;
        }

        if (!VmHaEnabled.valueIn(host.getDataCenterId())) {
            String message = String.format("Unable to investigate the host %s (%d), VM high availability manager is disabled.", host.getName(), hostId);
            if (logger.isDebugEnabled()) {
                logger.debug(message);
            }
            sendHostAlert(host, message);
            return Status.Alert;
        }

        Status hostState = null;
        for (Investigator investigator : investigators) {
            hostState = investigator.isAgentAlive(host);
            if (hostState != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("{} was able to determine host {} is in {}", investigator.getName(), host, hostState.toString());
                }
                return hostState;
            }
            if (logger.isDebugEnabled()) {
                logger.debug(investigator.getName() + " unable to determine the state of the host.  Moving on.");
            }
        }

        return hostState;
    }

    @Override
    public void scheduleRestartForVmsOnHost(final HostVO host, boolean investigate, ReasonType reasonType) {
        if (host.getType() != Host.Type.Routing) {
            return;
        }

        if (host.getHypervisorType() == HypervisorType.VMware || host.getHypervisorType() == HypervisorType.Hyperv) {
            logger.info("Don't restart VMs on host {} as it is a {} host", host, host.getHypervisorType().toString());
            return;
        }

        if (!VmHaEnabled.valueIn(host.getDataCenterId())) {
            String message = String.format("Unable to schedule restart for VMs on host %s, VM high availability manager is disabled.", host);
            if (logger.isDebugEnabled()) {
                logger.debug(message);
            }
            sendHostAlert(host, message);
            return;
        }

        logger.warn("Scheduling restart for VMs on host {}", host);

        final List<VMInstanceVO> vms = _instanceDao.listByHostId(host.getId());
        final DataCenterVO dcVO = _dcDao.findById(host.getDataCenterId());

        // send an email alert that the host is down
        StringBuilder sb = null;
        List<VMInstanceVO> reorderedVMList = new ArrayList<VMInstanceVO>();
        if ((vms != null) && !vms.isEmpty()) {
            sb = new StringBuilder();
            sb.append("  Starting HA on the following VMs:");
            // collect list of vm names for the alert email
            for (int i = 0; i < vms.size(); i++) {
                VMInstanceVO vm = vms.get(i);
                if (vm.getType() == VirtualMachine.Type.User) {
                    reorderedVMList.add(vm);
                } else {
                    reorderedVMList.add(0, vm);
                }
                if (vm.isHaEnabled()) {
                    sb.append(" " + vm.getHostName());
                }
            }
        }

        // send an email alert that the host is down, include VMs
        HostPodVO podVO = _podDao.findById(host.getPodId());
        String hostDesc = "name: " + host.getName() + " (id:" + host.getId() + "), availability zone: " + dcVO.getName() + ", pod: " + podVO.getName();
        _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_HOST, host.getDataCenterId(), host.getPodId(), "Host is down, " + hostDesc,
                "Host [" + hostDesc + "] is down." + ((sb != null) ? sb.toString() : ""));

        for (VMInstanceVO vm : reorderedVMList) {
            ServiceOfferingVO vmOffering = _serviceOfferingDao.findById(vm.getServiceOfferingId());
            if (_itMgr.isRootVolumeOnLocalStorage(vm.getId())) {
                if (logger.isDebugEnabled()){
                    logger.debug("Skipping HA on vm " + vm + ", because it uses local storage. Its fate is tied to the host.");
                }
                continue;
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Notifying HA Mgr of to restart vm {}", vm);
            }
            vm = _instanceDao.findByUuid(vm.getUuid());
            Long hostId = vm.getHostId();
            if (hostId != null && !hostId.equals(host.getId())) {
                logger.debug("VM {} is not on down host {} it is on other host {} VM HA is done", vm, host, hostId);
                continue;
            }
            scheduleRestart(vm, investigate, reasonType);
        }
    }

    @Override
    public boolean scheduleStop(VMInstanceVO vm, long hostId, WorkType type, ReasonType reasonType) {
        assert (type == WorkType.CheckStop || type == WorkType.ForceStop || type == WorkType.Stop);

        if (_haDao.hasBeenScheduled(vm.getId(), type)) {
            logger.info("There's already a job scheduled to stop " + vm);
            return false;
        }

        if (!VmHaEnabled.valueIn(vm.getDataCenterId())) {
            String message = String.format("Unable to schedule stop for the VM %s (%d) on host %d, VM high availability manager is disabled.", vm.getName(), vm.getId(), hostId);
            if (logger.isDebugEnabled()) {
                logger.debug(message);
            }
            sendVMAlert(vm, message);
            return false;
        }

        HaWorkVO work = new HaWorkVO(vm.getId(), vm.getType(), type, Step.Scheduled, hostId, vm.getState(), 0, vm.getUpdated(), reasonType);
        _haDao.persist(work);
        if (logger.isDebugEnabled()) {
            logger.debug("Scheduled " + work);
        }
        wakeupWorkers();
        return true;
    }

    @Override
    public boolean scheduleStop(VMInstanceVO vm, long hostId, WorkType type) {
        return scheduleStop(vm, hostId, type, null);
    }

    protected void wakeupWorkers() {
        logger.debug("Wakeup workers HA");
        for (WorkerThread worker : _workers) {
            worker.wakup();
        }
    }

    @Override
    public boolean scheduleMigration(final VMInstanceVO vm, HighAvailabilityManager.ReasonType reasonType) {
        if (vm.getHostId() == null) {
            return false;
        }
        if (!VmHaEnabled.valueIn(vm.getDataCenterId())) {
            String message = String.format("Unable to schedule migration for the VM %s on host %s, VM high availability manager is disabled.", vm, _hostDao.findById(vm.getHostId()));
            if (logger.isDebugEnabled()) {
                logger.debug(message);
            }
            sendVMAlert(vm, message);
            return false;
        }

        Long hostId = VirtualMachine.State.Migrating.equals(vm.getState()) ? vm.getLastHostId() : vm.getHostId();
        final HaWorkVO work = new HaWorkVO(vm.getId(), vm.getType(), WorkType.Migration, Step.Scheduled, vm.getHostId(), vm.getState(), 0, vm.getUpdated(), reasonType);
        _haDao.persist(work);
        logger.info("Scheduled migration work of VM {} from host {} with HAWork {}", vm, _hostDao.findById(vm.getHostId()), work);
        wakeupWorkers();
        return true;
    }

    @Override
    public boolean scheduleMigration(final VMInstanceVO vm) {
        return scheduleMigration(vm, null);
    }

    @Override
    public void scheduleRestart(VMInstanceVO vm, boolean investigate, ReasonType reasonType) {
        if (!VmHaEnabled.valueIn(vm.getDataCenterId())) {
            String message = String.format("Unable to schedule restart for the VM %s (%d), VM high availability manager is disabled.", vm.getName(), vm.getId());
            if (logger.isDebugEnabled()) {
                logger.debug(message);
            }
            sendVMAlert(vm, message);
            return;
        }

        logger.debug("HA schedule restart");
        Long hostId = vm.getHostId();
        if (hostId == null) {
            try {
                logger.debug("Found a vm that is scheduled to be restarted but has no host id: " + vm);
                _itMgr.advanceStop(vm.getUuid(), true);
            } catch (ResourceUnavailableException e) {
                assert false : "How do we hit this when force is true?";
                throw new CloudRuntimeException("Caught exception even though it should be handled.", e);
            } catch (OperationTimedoutException e) {
                assert false : "How do we hit this when force is true?";
                throw new CloudRuntimeException("Caught exception even though it should be handled.", e);
            } catch (ConcurrentOperationException e) {
                assert false : "How do we hit this when force is true?";
                throw new CloudRuntimeException("Caught exception even though it should be handled.", e);
            }
        }

        if (vm.getHypervisorType() == HypervisorType.VMware || vm.getHypervisorType() == HypervisorType.Hyperv) {
            logger.info("Skip HA for VMware VM or Hyperv VM" + vm.getInstanceName());
            return;
        }

        if (!investigate) {
            if (logger.isDebugEnabled()) {
                logger.debug("VM does not require investigation so I'm marking it as Stopped: " + vm.toString());
            }

            AlertManager.AlertType alertType = AlertManager.AlertType.ALERT_TYPE_USERVM;
            if (VirtualMachine.Type.DomainRouter.equals(vm.getType())) {
                alertType = AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER;
            } else if (VirtualMachine.Type.ConsoleProxy.equals(vm.getType())) {
                alertType = AlertManager.AlertType.ALERT_TYPE_CONSOLE_PROXY;
            } else if (VirtualMachine.Type.SecondaryStorageVm.equals(vm.getType())) {
                alertType = AlertManager.AlertType.ALERT_TYPE_SSVM;
            }

            if (!(ForceHA.value() || vm.isHaEnabled())) {
                String hostDesc = "id:" + vm.getHostId() + ", availability zone id:" + vm.getDataCenterId() + ", pod id:" + vm.getPodIdToDeployIn();
                _alertMgr.sendAlert(alertType, vm.getDataCenterId(), vm.getPodIdToDeployIn(), "VM (name: " + vm.getHostName() + ", id: " + vm.getId() +
                    ") stopped unexpectedly on host " + hostDesc, "Virtual Machine " + vm.getHostName() + " (id: " + vm.getId() + ") running on host [" + vm.getHostId() +
                    "] stopped unexpectedly.");

                if (logger.isDebugEnabled()) {
                    logger.debug("VM is not HA enabled so we're done.");
                }
            }

            try {
                _itMgr.advanceStop(vm.getUuid(), true);
                vm = _instanceDao.findByUuid(vm.getUuid());
            } catch (ResourceUnavailableException e) {
                assert false : "How do we hit this when force is true?";
                throw new CloudRuntimeException("Caught exception even though it should be handled.", e);
            } catch (OperationTimedoutException e) {
                assert false : "How do we hit this when force is true?";
                throw new CloudRuntimeException("Caught exception even though it should be handled.", e);
            } catch (ConcurrentOperationException e) {
                assert false : "How do we hit this when force is true?";
                throw new CloudRuntimeException("Caught exception even though it should be handled.", e);
            }
        }

        if (vm.getHypervisorType() == HypervisorType.VMware) {
            logger.info("Skip HA for VMware VM " + vm.getInstanceName());
            return;
        }

        List<HaWorkVO> items = _haDao.findPreviousHA(vm.getId());
        int timesTried = 0;
        for (HaWorkVO item : items) {
            if (timesTried < item.getTimesTried() && !item.canScheduleNew(_timeBetweenFailures)) {
                timesTried = item.getTimesTried();
                break;
            }
        }

        if (hostId == null) {
            hostId = vm.getLastHostId();
        }

        HaWorkVO work = new HaWorkVO(vm.getId(), vm.getType(), WorkType.HA, investigate ? Step.Investigating : Step.Scheduled,
                hostId != null ? hostId : 0L, vm.getState(), timesTried, vm.getUpdated(), reasonType);
        _haDao.persist(work);

        if (logger.isInfoEnabled()) {
            logger.info("Schedule vm for HA:  " + vm);
        }

        wakeupWorkers();
    }

    @Override
    public void scheduleRestart(VMInstanceVO vm, boolean investigate) {
        scheduleRestart(vm, investigate, null);
    }

    private void startVm(VirtualMachine vm, Map<VirtualMachineProfile.Param, Object> params,
           DeploymentPlanner planner) throws InsufficientCapacityException, ResourceUnavailableException,
            ConcurrentOperationException, OperationTimedoutException {
        CallContext ctx = CallContext.register(CallContext.current(), ApiCommandResourceType.VirtualMachine);
        ctx.setEventResourceId(vm.getId());
        try {
            switch (vm.getType()) {
                case DomainRouter:
                    ctx.setEventResourceType(ApiCommandResourceType.DomainRouter);
                    routerService.startRouterForHA(vm, params, planner);
                    break;
                case ConsoleProxy:
                    ctx.setEventResourceType(ApiCommandResourceType.ConsoleProxy);
                    consoleProxyManager.startProxyForHA(vm, params, planner);
                    break;
                case SecondaryStorageVm:
                    ctx.setEventResourceType(ApiCommandResourceType.SystemVm);
                    secondaryStorageVmManager.startSecStorageVmForHA(vm, params, planner);
                    break;
                case User:
                    userVmManager.startVirtualMachineForHA(vm, params, planner);
                    break;
                default:
                    _itMgr.advanceStart(vm.getUuid(), params, planner);
            }
        } finally {
            CallContext.unregister();
        }
    }

    protected Long restart(final HaWorkVO work) {
        logger.debug("RESTART with HAWORK");
        List<HaWorkVO> items = _haDao.listFutureHaWorkForVm(work.getInstanceId(), work.getId());
        if (items.size() > 0) {
            StringBuilder str = new StringBuilder("Cancelling this work item because newer ones have been scheduled.  Work Ids = [");
            for (HaWorkVO item : items) {
                str.append(item.getId()).append(", ");
            }
            str.delete(str.length() - 2, str.length()).append("]");
            logger.info(str.toString());
            return null;
        }

        items = _haDao.listRunningHaWorkForVm(work.getInstanceId());
        if (items.size() > 0) {
            StringBuilder str = new StringBuilder("Waiting because there's HA work being executed on an item currently.  Work Ids =[");
            for (HaWorkVO item : items) {
                str.append(item.getId()).append(", ");
            }
            str.delete(str.length() - 2, str.length()).append("]");
            logger.info(str.toString());
            return (System.currentTimeMillis() >> 10) + _investigateRetryInterval;
        }

        long vmId = work.getInstanceId();

        VirtualMachine vm = _itMgr.findById(work.getInstanceId());
        if (vm == null) {
            logger.info("Unable to find vm: " + vmId);
            return null;
        }
        if (checkAndCancelWorkIfNeeded(work)) {
            return null;
        }

        logger.info("HA on " + vm);
        if (vm.getState() != work.getPreviousState() || vm.getUpdated() != work.getUpdateTime()) {
            logger.info("VM " + vm + " has been changed.  Current State = " + vm.getState() + " Previous State = " + work.getPreviousState() + " last updated = " +
                vm.getUpdated() + " previous updated = " + work.getUpdateTime());
            return null;
        }

        AlertManager.AlertType alertType = AlertManager.AlertType.ALERT_TYPE_USERVM;
        if (VirtualMachine.Type.DomainRouter.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER;
        } else if (VirtualMachine.Type.ConsoleProxy.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_CONSOLE_PROXY;
        } else if (VirtualMachine.Type.SecondaryStorageVm.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_SSVM;
        }

        HostVO host = _hostDao.findById(work.getHostId());
        boolean isHostRemoved = false;
        if (host == null) {
            host = _hostDao.findByIdIncludingRemoved(work.getHostId());
            if (host != null) {
                logger.debug("VM {} is now no longer on host {} as the host is removed", vm, host);
                isHostRemoved = true;
            }
        }

        DataCenterVO dcVO = _dcDao.findById(host.getDataCenterId());
        HostPodVO podVO = _podDao.findById(host.getPodId());
        String hostDesc = String.format("%s, availability zone: %s, pod: %s", host, dcVO.getName(), podVO.getName());

        Boolean alive = null;
        if (work.getStep() == Step.Investigating) {
            if (!isHostRemoved) {
                if (vm.getHostId() == null || vm.getHostId() != work.getHostId()) {
                    logger.info("VM {} is now no longer on host {}", vm, host);
                    return null;
                }

                Investigator investigator = null;
                for (Investigator it : investigators) {
                    investigator = it;
                    try
                    {
                        alive = investigator.isVmAlive(vm, host);
                        logger.info(investigator.getName() + " found " + vm + " to be alive? " + alive);
                        break;
                    } catch (UnknownVM e) {
                        logger.info(investigator.getName() + " could not find " + vm);
                    }
                }

                boolean fenced = false;
                if (alive == null) {
                    logger.debug("Fencing off VM that we don't know the state of");
                    for (FenceBuilder fb : fenceBuilders) {
                        Boolean result = fb.fenceOff(vm, host);
                        logger.info("Fencer " + fb.getName() + " returned " + result);
                        if (result != null && result) {
                            fenced = true;
                            break;
                        }
                    }

                } else if (!alive) {
                    fenced = true;
                } else {
                    logger.debug("VM {} is found to be alive by {}", vm, investigator.getName());
                    if (host.getStatus() == Status.Up) {
                        logger.info(vm + " is alive and host is up. No need to restart it.");
                        return null;
                    } else {
                        logger.debug("Rescheduling because the host is not up but the vm is alive");
                        return (System.currentTimeMillis() >> 10) + _investigateRetryInterval;
                    }
                }

                if (!fenced) {
                    logger.debug("We were unable to fence off the VM " + vm);
                    _alertMgr.sendAlert(alertType, vm.getDataCenterId(), vm.getPodIdToDeployIn(), "Unable to restart " + vm.getHostName() +
                        " which was running on host " + hostDesc, "Insufficient capacity to restart VM, name: " + vm.getHostName() + ", id: " + vmId +
                        " which was running on host " + hostDesc);
                    return (System.currentTimeMillis() >> 10) + _restartRetryInterval;
                }

                try {
                    _itMgr.advanceStop(vm.getUuid(), true);
                } catch (ResourceUnavailableException e) {
                    assert false : "How do we hit this when force is true?";
                    throw new CloudRuntimeException("Caught exception even though it should be handled.", e);
                } catch (OperationTimedoutException e) {
                    assert false : "How do we hit this when force is true?";
                    throw new CloudRuntimeException("Caught exception even though it should be handled.", e);
                } catch (ConcurrentOperationException e) {
                    assert false : "How do we hit this when force is true?";
                    throw new CloudRuntimeException("Caught exception even though it should be handled.", e);
                }

                work.setStep(Step.Scheduled);
                _haDao.update(work.getId(), work);
            } else {
                logger.debug("How come that HA step is Investigating and the host is removed? Calling forced Stop on Vm anyways");
                try {
                    _itMgr.advanceStop(vm.getUuid(), true);
                } catch (ResourceUnavailableException e) {
                    assert false : "How do we hit this when force is true?";
                    throw new CloudRuntimeException("Caught exception even though it should be handled.", e);
                } catch (OperationTimedoutException e) {
                    assert false : "How do we hit this when force is true?";
                    throw new CloudRuntimeException("Caught exception even though it should be handled.", e);
                } catch (ConcurrentOperationException e) {
                    assert false : "How do we hit this when force is true?";
                    throw new CloudRuntimeException("Caught exception even though it should be handled.", e);
                }
            }
        }

        vm = _itMgr.findById(vm.getId());

        if (!ForceHA.value() && !vm.isHaEnabled()) {
            if (logger.isDebugEnabled()) {
                logger.debug("VM is not HA enabled so we're done.");
            }
            return null; // VM doesn't require HA
        }

        if ((host == null || host.getRemoved() != null || host.getState() != Status.Up)
                 && !volumeMgr.canVmRestartOnAnotherServer(vm.getId())) {
            if (logger.isDebugEnabled()) {
                logger.debug("VM can not restart on another server.");
            }
            return null;
        }

        try {
            HashMap<VirtualMachineProfile.Param, Object> params = new HashMap<VirtualMachineProfile.Param, Object>();
            if (_haTag != null) {
                params.put(VirtualMachineProfile.Param.HaTag, _haTag);
            }
            WorkType wt = work.getWorkType();
            if (wt.equals(WorkType.HA)) {
                params.put(VirtualMachineProfile.Param.HaOperation, true);
            }

            try{
                if (HypervisorType.KVM == host.getHypervisorType()) {
                    List<VolumeVO> volumes = volumeDao.findByInstance(vmId);
                    for (VolumeVO volumeVO : volumes) {
                        //detach the volumes from all clusters before starting the VM on another host.
                        if (volumeVO.getPoolType() == StoragePoolType.StorPool) {
                            DataStoreProvider storeProvider = dataStoreProviderMgr.getDataStoreProvider(volumeVO.getPoolType().name());
                            DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();
                            if (storeDriver instanceof PrimaryDataStoreDriver) {
                                PrimaryDataStoreDriver primaryStoreDriver = (PrimaryDataStoreDriver)storeDriver;
                                primaryStoreDriver.detachVolumeFromAllStorageNodes(volumeVO);
                            }
                        }
                    }
                }
                // First try starting the vm with its original planner, if it doesn't succeed send HAPlanner as its an emergency.
                startVm(vm, params, null);
            } catch (InsufficientCapacityException e){
                logger.warn("Failed to deploy vm {} with original planner, sending HAPlanner", vm);
                startVm(vm, params, _haPlanners.get(0));
            }

            VMInstanceVO started = _instanceDao.findById(vm.getId());
            if (started != null && started.getState() == VirtualMachine.State.Running) {
                String message = String.format("HA starting VM: %s (%s)", started.getHostName(), started.getInstanceName());
                HostVO hostVmHasStarted = _hostDao.findById(started.getHostId());
                logger.info(String.format("HA is now restarting %s on %s", started, hostVmHasStarted));
                _alertMgr.sendAlert(alertType, vm.getDataCenterId(), vm.getPodIdToDeployIn(), message, message);
                return null;
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Rescheduling VM " + vm.toString() + " to try again in " + _restartRetryInterval);
            }
        } catch (final InsufficientCapacityException e) {
            logger.warn("Unable to restart " + vm.toString() + " due to " + e.getMessage());
            _alertMgr.sendAlert(alertType, vm.getDataCenterId(), vm.getPodIdToDeployIn(), "Unable to restart " + vm.getHostName() + " which was running on host " +
                hostDesc, String.format("Insufficient capacity to restart VM, name: %s, id: %d uuid: %s which was running on host %s", vm.getHostName(), vmId, vm.getUuid(), hostDesc));
        } catch (final ResourceUnavailableException e) {
            logger.warn("Unable to restart " + vm.toString() + " due to " + e.getMessage());
            _alertMgr.sendAlert(alertType, vm.getDataCenterId(), vm.getPodIdToDeployIn(), "Unable to restart " + vm.getHostName() + " which was running on host " +
                hostDesc, String.format("The resource is unavailable for trying to restart VM, name: %s, id: %d uuid: %s which was running on host %s", vm.getHostName(), vmId, vm.getUuid(), hostDesc));
        } catch (ConcurrentOperationException e) {
            logger.warn("Unable to restart " + vm.toString() + " due to " + e.getMessage());
            _alertMgr.sendAlert(alertType, vm.getDataCenterId(), vm.getPodIdToDeployIn(), "Unable to restart " + vm.getHostName() + " which was running on host " +
                hostDesc, String.format("The Storage is unavailable for trying to restart VM, name: %s, id: %d uuid: %s which was running on host %s", vm.getHostName(), vmId, vm.getUuid(), hostDesc));
        } catch (OperationTimedoutException e) {
            logger.warn("Unable to restart " + vm.toString() + " due to " + e.getMessage());
            _alertMgr.sendAlert(alertType, vm.getDataCenterId(), vm.getPodIdToDeployIn(), "Unable to restart " + vm.getHostName() + " which was running on host " +
                    hostDesc, String.format("The operation timed out while trying to restart VM, name: %s, id: %d uuid: %s which was running on host %s", vm.getHostName(), vmId, vm.getUuid(), hostDesc));
        }
        vm = _itMgr.findById(vm.getId());
        work.setUpdateTime(vm.getUpdated());
        work.setPreviousState(vm.getState());
        return (System.currentTimeMillis() >> 10) + _restartRetryInterval;
    }

    protected boolean checkAndCancelWorkIfNeeded(final HaWorkVO work) {
        if (!Step.Investigating.equals(work.getStep())) {
            return false;
        }
        if (!CancellableWorkReasonTypes.contains(work.getReasonType())) {
            return false;
        }
        Status hostStatus = investigate(work.getHostId());
        if (!Status.Up.equals(hostStatus)) {
            return false;
        }
        logger.debug("Cancelling {} as it is not needed anymore", () -> work);
        work.setStep(Step.Cancelled);
        return true;
    }

    public Long migrate(final HaWorkVO work) {
        long vmId = work.getInstanceId();
        long srcHostId = work.getHostId();
        HostVO srcHost = _hostDao.findById(srcHostId);

        VMInstanceVO vm = _instanceDao.findById(vmId);
        if (vm == null) {
            logger.info("Unable to find vm: " + vmId + ", skipping migrate.");
            return null;
        }
        if (checkAndCancelWorkIfNeeded(work)) {
            return null;
        }
        logger.info("Migration attempt: for VM {}from host {}. Starting attempt: {}/{} times.", vm, srcHost, 1 + work.getTimesTried(), _maxRetries);

        if (VirtualMachine.State.Stopped.equals(vm.getState())) {
            logger.info(String.format("vm %s is Stopped, skipping migrate.", vm));
            return null;
        }
        if (VirtualMachine.State.Running.equals(vm.getState()) && srcHostId != vm.getHostId()) {
            logger.info(String.format("VM %s is running on a different host %s, skipping migration", vm, vm.getHostId()));
            return null;
        }
        logger.info("Migration attempt: for VM " + vm.getUuid() + "from host id " + srcHostId +
                ". Starting attempt: " + (1 + work.getTimesTried()) + "/" + _maxRetries + " times.");

        try {
            work.setStep(Step.Migrating);
            _haDao.update(work.getId(), work);

            // First try starting the vm with its original planner, if it doesn't succeed send HAPlanner as its an emergency.
            _itMgr.migrateAway(vm.getUuid(), srcHostId);
            return null;
        } catch (InsufficientServerCapacityException e) {
            logger.warn("Migration attempt: Insufficient capacity for migrating a VM {} from source host {}. Exception: {}", vm, srcHost, e.getMessage());
            _resourceMgr.migrateAwayFailed(srcHostId, vmId);
            return (System.currentTimeMillis() >> 10) + _migrateRetryInterval;
        } catch (Exception e) {
            logger.warn("Migration attempt: Unexpected exception occurred when attempting migration of {} {}", vm, e.getMessage());
            throw e;
        }
    }

    @Override
    public boolean scheduleDestroy(VMInstanceVO vm, long hostId, ReasonType reasonType) {
        if (!VmHaEnabled.valueIn(vm.getDataCenterId())) {
            String message = String.format("Unable to schedule destroy for the VM %s (%d) on host %d, VM high availability manager is disabled.", vm.getName(), vm.getId(), hostId);
            if (logger.isDebugEnabled()) {
                logger.debug(message);
            }
            sendVMAlert(vm, message);
            return false;
        }

        final HaWorkVO work = new HaWorkVO(vm.getId(), vm.getType(), WorkType.Destroy, Step.Scheduled, hostId, vm.getState(), 0, vm.getUpdated(), reasonType);
        _haDao.persist(work);
        if (logger.isDebugEnabled()) {
            logger.debug("Scheduled " + work.toString());
        }
        wakeupWorkers();
        return true;
    }

    @Override
    public void cancelDestroy(VMInstanceVO vm, Long hostId) {
        _haDao.delete(vm.getId(), WorkType.Destroy);
    }

    private void stopVMWithCleanup(VirtualMachine vm, VirtualMachine.State state) throws OperationTimedoutException, ResourceUnavailableException {
        if (VirtualMachine.State.Running.equals(state)) {
            _itMgr.advanceStop(vm.getUuid(), true);
        }
    }

    private void destroyVM(VirtualMachine vm, boolean expunge) throws OperationTimedoutException, AgentUnavailableException {
        logger.info("Destroying " + vm.toString());
        if (VirtualMachine.Type.ConsoleProxy.equals(vm.getType())) {
            consoleProxyManager.destroyProxy(vm.getId());
        } else if (VirtualMachine.Type.SecondaryStorageVm.equals(vm.getType())) {
            secondaryStorageVmManager.destroySecStorageVm(vm.getId());
        } else {
            _itMgr.destroy(vm.getUuid(), expunge);
        }
    }

    protected Long destroyVM(final HaWorkVO work) {
        final VirtualMachine vm = _itMgr.findById(work.getInstanceId());
        if (vm == null) {
            logger.info("No longer can find VM " + work.getInstanceId() + ". Throwing away " + work);
            return null;
        }
        if (checkAndCancelWorkIfNeeded(work)) {
            return null;
        }
        boolean expunge = VirtualMachine.Type.SecondaryStorageVm.equals(vm.getType())
                || VirtualMachine.Type.ConsoleProxy.equals(vm.getType());
        if (!expunge && VirtualMachine.State.Destroyed.equals(work.getPreviousState())) {
            logger.info("VM {} already in {} state. Throwing away {}", vm, vm.getState(), work);
            return null;
        }
        try {
            stopVMWithCleanup(vm, work.getPreviousState());
            if (!VirtualMachine.State.Expunging.equals(work.getPreviousState())) {
                destroyVM(vm, expunge);
                return null;
            } else {
                logger.info("VM {} still in {} state.", vm, vm.getState());
            }
        } catch (final AgentUnavailableException e) {
            logger.debug("Agent is not available" + e.getMessage());
        } catch (OperationTimedoutException e) {
            logger.debug("operation timed out: " + e.getMessage());
        } catch (ConcurrentOperationException e) {
            logger.debug("concurrent operation: " + e.getMessage());
        } catch (ResourceUnavailableException e) {
            logger.debug("Resource unavailable: " + e.getMessage());
        }

        return (System.currentTimeMillis() >> 10) + _stopRetryInterval;
    }

    protected Long stopVM(final HaWorkVO work) throws ConcurrentOperationException {
        VirtualMachine vm = _itMgr.findById(work.getInstanceId());
        if (vm == null) {
            logger.info("No longer can find VM " + work.getInstanceId() + ". Throwing away " + work);
            work.setStep(Step.Done);
            return null;
        }
        if (checkAndCancelWorkIfNeeded(work)) {
            return null;
        }
        logger.info("Stopping " + vm);
        try {
            if (work.getWorkType() == WorkType.Stop) {
                _itMgr.advanceStop(vm.getUuid(), false);
                logger.info("Successfully stopped " + vm);
                return null;
            } else if (work.getWorkType() == WorkType.CheckStop) {
                if ((vm.getState() != work.getPreviousState()) || vm.getUpdated() != work.getUpdateTime() || vm.getHostId() == null ||
                    vm.getHostId().longValue() != work.getHostId()) {
                    HostVO scheduledHost = _hostDao.findById(work.getHostId());
                    HostVO currentHost = vm.getHostId() != null ? _hostDao.findById(vm.getHostId()) : null;
                    logger.info("{} is different now.  Scheduled Host: {} Current Host: {} State: {}", vm, scheduledHost, currentHost != null ? currentHost : "none", vm.getState());
                    return null;
                }

                _itMgr.advanceStop(vm.getUuid(), false);
                logger.info("Stop for " + vm + " was successful");
                return null;
            } else if (work.getWorkType() == WorkType.ForceStop) {
                if ((vm.getState() != work.getPreviousState()) || vm.getUpdated() != work.getUpdateTime() || vm.getHostId() == null ||
                    vm.getHostId().longValue() != work.getHostId()) {
                    HostVO scheduledHost = _hostDao.findById(work.getHostId());
                    HostVO currentHost = vm.getHostId() != null ? _hostDao.findById(vm.getHostId()) : null;
                    logger.info("{} is different now.  Scheduled Host: {} Current Host: {} State: {}", vm, scheduledHost, currentHost != null ? currentHost : "none", vm.getState());
                    return null;
                }

                _itMgr.advanceStop(vm.getUuid(), true);
                logger.info("Stop for " + vm + " was successful");
                return null;
            } else {
                assert false : "Who decided there's other steps but didn't modify the guy who does the work?";
            }
        } catch (final ResourceUnavailableException e) {
            logger.debug("Agnet is not available" + e.getMessage());
        } catch (OperationTimedoutException e) {
            logger.debug("operation timed out: " + e.getMessage());
        }

        return (System.currentTimeMillis() >> 10) + _stopRetryInterval;
    }

    @Override
    public void cancelScheduledMigrations(final HostVO host) {
        WorkType type = host.getType() == HostVO.Type.Storage ? WorkType.Stop : WorkType.Migration;
        logger.info("Canceling all scheduled migrations from host {}", host);
        _haDao.deleteMigrationWorkItems(host.getId(), type, _serverId);
    }

    @Override
    public List<VMInstanceVO> findTakenMigrationWork() {
        List<HaWorkVO> works = _haDao.findTakenWorkItems(WorkType.Migration);
        List<VMInstanceVO> vms = new ArrayList<VMInstanceVO>(works.size());
        for (HaWorkVO work : works) {
            VMInstanceVO vm = _instanceDao.findById(work.getInstanceId());
            if (vm != null) {
                vms.add(vm);
            }
        }
        return vms;
    }

    private void rescheduleWork(final HaWorkVO work, final long nextTime) {
        work.setTimeToTry(nextTime);
        work.setTimesTried(work.getTimesTried() + 1);
        work.setServerId(null);
        work.setDateTaken(null);
    }

    private long getRescheduleTime(WorkType workType) {
        switch (workType) {
            case Migration:
                return ((System.currentTimeMillis() >> 10) + _migrateRetryInterval);
            case HA:
                return ((System.currentTimeMillis() >> 10) + _restartRetryInterval);
            case Stop:
            case CheckStop:
            case ForceStop:
            case Destroy:
                return ((System.currentTimeMillis() >> 10) + _stopRetryInterval);
        }
        return 0;
    }

    private void processWork(final HaWorkVO work) {
        final WorkType wt = work.getWorkType();
        final VMInstanceVO vm = _instanceDao.findById(work.getInstanceId());
        try {
            if (vm != null && !VmHaEnabled.valueIn(vm.getDataCenterId())) {
                if (logger.isDebugEnabled()) {
                    logger.debug(String.format("VM high availability manager is disabled, rescheduling the HA work %s, for the VM %s (id) to retry later in case VM high availability manager is enabled on retry attempt", work, vm.getName(), vm.getId()));
                }
                long nextTime = getRescheduleTime(wt);
                rescheduleWork(work, nextTime);
                return;
            }

            Long nextTime = null;
            if (wt == WorkType.Migration) {
                nextTime = migrate(work);
            } else if (wt == WorkType.HA) {
                nextTime = restart(work);
            } else if (wt == WorkType.Stop || wt == WorkType.CheckStop || wt == WorkType.ForceStop) {
                nextTime = stopVM(work);
            } else if (wt == WorkType.Destroy) {
                nextTime = destroyVM(work);
            } else {
                assert false : "How did we get here with " + wt.toString();
                return;
            }

            if (nextTime == null) {
                logger.info("Completed work " + work + ". Took " + (work.getTimesTried() + 1) + "/" + _maxRetries + " attempts.");
                work.setStep(Step.Done);
            } else {
                rescheduleWork(work, nextTime.longValue());
            }
        } catch (Exception e) {
            logger.warn("Encountered unhandled exception during HA process, reschedule work", e);

            long nextTime = getRescheduleTime(wt);
            rescheduleWork(work, nextTime);

            // if restart failed in the middle due to exception, VM state may has been changed
            // recapture into the HA worker so that it can really continue in it next turn
            if (vm != null) {
                work.setUpdateTime(vm.getUpdated());
                work.setPreviousState(vm.getState());
            }
        } finally {
            if (!Step.Done.equals(work.getStep())) {
                if (work.getTimesTried() >= _maxRetries) {
                    logger.warn("Giving up, retried max " + work.getTimesTried() + "/" + _maxRetries + " times for work: " + work);
                    work.setStep(Step.Done);
                } else {
                    logger.warn("Rescheduling work " + work + " to try again at " + new Date(work.getTimeToTry() << 10) +
                            ". Finished attempt " + work.getTimesTried() + "/" + _maxRetries + " times.");
                }
            }
            _haDao.update(work.getId(), work);
        }
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> xmlParams) throws ConfigurationException {
        _serverId = _msServer.getId();

        final Map<String, String> params = _configDao.getConfiguration(Long.toHexString(_serverId),
            xmlParams);

        final int count = HAWorkers.value();
        _workers = new WorkerThread[count];
        for (int i = 0; i < _workers.length; i++) {
            _workers[i] = new WorkerThread("HA-Worker-" + i);
        }

        _timeToSleep = TimeToSleep.value() * SECONDS_TO_MILLISECONDS_FACTOR;
        _maxRetries = MigrationMaxRetries.value();
        _timeBetweenFailures = TimeBetweenFailures.value() * SECONDS_TO_MILLISECONDS_FACTOR;
        _timeBetweenCleanups = TimeBetweenCleanup.value();
        _stopRetryInterval = StopRetryInterval.value();
        _restartRetryInterval = RestartRetryInterval.value();
        _investigateRetryInterval = InvestigateRetryInterval.value();
        _migrateRetryInterval = MigrateRetryInterval.value();

        _instance = params.get("instance");
        if (_instance == null) {
            _instance = "VMOPS";
        }

        _haTag = params.get("ha.tag");

        _haDao.releaseWorkItems(_serverId);

        _stopped = true;

        _executor = Executors.newScheduledThreadPool(count, new NamedThreadFactory("HA"));

        return true;
    }

    @Override
    public boolean start() {
        _stopped = false;

        _haDao.markPendingWorksAsInvestigating();

        for (final WorkerThread thread : _workers) {
            thread.start();
        }

        _executor.scheduleAtFixedRate(new CleanupTask(), _timeBetweenCleanups, _timeBetweenCleanups, TimeUnit.SECONDS);

        return true;
    }

    @Override
    public boolean stop() {
        _stopped = true;

        wakeupWorkers();

        _executor.shutdown();

        _haDao.markServerPendingWorksAsInvestigating(_msServer.getId());

        return true;
    }

    protected class CleanupTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            logger.info("HA Cleanup Thread Running");

            try {
                _haDao.cleanup(System.currentTimeMillis() - _timeBetweenFailures);
            } catch (Exception e) {
                logger.warn("Error while cleaning up", e);
            }
        }
    }

    protected class WorkerThread extends Thread {
        public WorkerThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            logger.info("Starting work");
            try {
                synchronized (this) {
                    wait(_timeToSleep);
                }
            } catch (final InterruptedException e) {
                logger.info("Interrupted");
            }
            logger.info("Starting work");

            while (!_stopped) {
                _managedContext.runWithContext(new Runnable() {
                    @Override
                    public void run() {
                        runWithContext();
                    }
                });
            }
            logger.info("Time to go home!");
        }

        private void runWithContext() {
            HaWorkVO work = null;
            try {
                logger.trace("Checking the database for work");
                work = _haDao.take(_serverId);
                if (work == null) {
                    try {
                        synchronized (this) {
                            wait(_timeToSleep);
                        }
                        return;
                    } catch (final InterruptedException e) {
                        logger.info("Interrupted");
                        return;
                    }
                }

                ThreadContext.push("work-" + work.getId());
                logger.info("Processing work " + work);
                processWork(work);
            } catch (final Throwable th) {
                logger.error("Caught this throwable, ", th);
            } finally {
                if (work != null) {
                    ThreadContext.pop();
                }
            }
        }

        public synchronized void wakup() {
            notifyAll();
        }
    }

    @Override
    public void onManagementNodeJoined(List<? extends ManagementServerHost> nodeList, long selfNodeId) {
    }

    @Override
    public void onManagementNodeLeft(List<? extends ManagementServerHost> nodeList, long selfNodeId) {
        for (ManagementServerHost node : nodeList) {
            _haDao.releaseWorkItems(node.getMsid());
        }
    }

    @Override
    public void onManagementNodeIsolated() {
    }

    @Override
    public String getHaTag() {
        return _haTag;
    }

    @Override
    public DeploymentPlanner getHAPlanner() {
        return _haPlanners.get(0);
    }

    @Override
    public boolean hasPendingHaWork(long vmId) {
        List<HaWorkVO> haWorks = _haDao.listPendingHaWorkForVm(vmId);
        return haWorks.size() > 0;
    }

    @Override
    public boolean hasPendingMigrationsWork(long vmId) {
        List<HaWorkVO> haWorks = _haDao.listPendingMigrationsForVm(vmId);
        for (HaWorkVO work : haWorks) {
            if (work.getTimesTried() <= _maxRetries) {
                return true;
            } else {
                logger.warn("HAWork Job of migration type " + work + " found in database which has max " +
                        "retries more than " + _maxRetries + " but still not in Done, Cancelled, or Error State");
            }
        }
        return false;
    }

    /**
     * @return The name of the component that provided this configuration
     * variable.  This value is saved in the database so someone can easily
     * identify who provides this variable.
     **/
    @Override
    public String getConfigComponentName() {
        return HighAvailabilityManager.class.getSimpleName();
    }

    /**
     * @return The list of config keys provided by this configuable.
     */
    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey[] {TimeBetweenCleanup, MigrationMaxRetries, TimeToSleep, TimeBetweenFailures,
            StopRetryInterval, RestartRetryInterval, MigrateRetryInterval, InvestigateRetryInterval,
            HAWorkers, ForceHA, VmHaEnabled, VmHaAlertsEnabled, KvmHAFenceHostIfHeartbeatFailsOnStorage};
    }

    @Override
    public int expungeWorkItemsByVmList(List<Long> vmIds, Long batchSize) {
        return _haDao.expungeByVmList(vmIds, batchSize);
    }

    private void sendVMAlert(VMInstanceVO vm, String message) {
        if (vm == null || !VmHaAlertsEnabled.valueIn(vm.getDataCenterId())) {
            return;
        }
        AlertManager.AlertType alertType = AlertManager.AlertType.ALERT_TYPE_USERVM;
        if (VirtualMachine.Type.DomainRouter.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER;
        } else if (VirtualMachine.Type.ConsoleProxy.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_CONSOLE_PROXY;
        } else if (VirtualMachine.Type.SecondaryStorageVm.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_SSVM;
        }
        _alertMgr.sendAlert(alertType, vm.getDataCenterId(), vm.getPodIdToDeployIn(), message, message);
    }

    private void sendHostAlert(HostVO host, String message) {
        if (host == null || !VmHaAlertsEnabled.valueIn(host.getDataCenterId())) {
            return;
        }
        _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_HOST, host.getDataCenterId(), host.getPodId(), message, message);
    }
}
