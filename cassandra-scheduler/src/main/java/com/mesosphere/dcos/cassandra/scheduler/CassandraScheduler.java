package com.mesosphere.dcos.cassandra.scheduler;

import com.google.common.eventbus.EventBus;
import com.google.inject.Inject;
import com.mesosphere.dcos.cassandra.scheduler.backup.BackupManager;
import com.mesosphere.dcos.cassandra.scheduler.config.ConfigurationManager;
import com.mesosphere.dcos.cassandra.scheduler.config.IdentityManager;
import com.mesosphere.dcos.cassandra.scheduler.config.MesosConfig;
import com.mesosphere.dcos.cassandra.scheduler.offer.LogOperationRecorder;
import com.mesosphere.dcos.cassandra.scheduler.offer.PersistentOfferRequirementProvider;
import com.mesosphere.dcos.cassandra.scheduler.offer.PersistentOperationRecorder;
import com.mesosphere.dcos.cassandra.scheduler.persistence.PersistenceException;
import com.mesosphere.dcos.cassandra.scheduler.plan.CassandraBlock;
import com.mesosphere.dcos.cassandra.scheduler.plan.CassandraPlan;
import com.mesosphere.dcos.cassandra.scheduler.plan.PlanFactory;
import com.mesosphere.dcos.cassandra.scheduler.tasks.CassandraTasks;
import io.dropwizard.lifecycle.Managed;
import org.apache.mesos.MesosSchedulerDriver;
import org.apache.mesos.Protos;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;
import org.apache.mesos.offer.OfferAccepter;
import org.apache.mesos.scheduler.plan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class CassandraScheduler implements Scheduler, Managed {
    private final static Logger LOGGER = LoggerFactory.getLogger(
            CassandraScheduler.class);

    private MesosSchedulerDriver driver;
    private IdentityManager identityManager;
    private ConfigurationManager configurationManager;
    private MesosConfig mesosConfig;
    private PlanManager planManager;
    private PlanScheduler planScheduler;
    private CassandraRepairScheduler repairScheduler;
    private OfferAccepter offerAccepter;
    private PersistentOfferRequirementProvider offerRequirementProvider;
    private CassandraTasks cassandraTasks;
    private EventBus eventBus;
    private BackupManager backupManager;

    @Inject
    public CassandraScheduler(
            ConfigurationManager configurationManager,
            IdentityManager identityManager,
            MesosConfig mesosConfig,
            PersistentOfferRequirementProvider offerRequirementProvider,
            CassandraTasks cassandraTasks,
            EventBus eventBus,
            BackupManager backupManager) {
        this.eventBus = eventBus;
        this.mesosConfig = mesosConfig;
        this.backupManager = backupManager;
        this.cassandraTasks = cassandraTasks;
        this.identityManager = identityManager;
        this.configurationManager = configurationManager;
        this.offerRequirementProvider = offerRequirementProvider;
    }

    @Override
    public void start() throws Exception {
        registerFramework();
        eventBus.register(cassandraTasks);
    }

    @Override
    public void stop() throws Exception {
        if (this.driver != null) {
            LOGGER.info("Aborting driver...");
            final Protos.Status driverStatus = this.driver.abort();
            LOGGER.info("Aborted driver with status: {}", driverStatus);
        }
    }

    @Override
    public void registered(SchedulerDriver driver,
                           Protos.FrameworkID frameworkId,
                           Protos.MasterInfo masterInfo) {
        final String frameworkIdValue = frameworkId.getValue();
        LOGGER.info("Framework registered! ID = {}", frameworkIdValue);
        try {
            identityManager.register(frameworkIdValue);
        } catch (PersistenceException e) {
            LOGGER.error("Error storing framework id: {}", e);
            throw new RuntimeException("Error storing framework id", e);
        }

        offerAccepter = new OfferAccepter(Arrays.asList(
                new LogOperationRecorder(),
                new PersistentOperationRecorder(cassandraTasks)));

        final CassandraPlan plan = PlanFactory.getPlan(
                offerRequirementProvider,
                configurationManager,
                eventBus,
                cassandraTasks);
        planManager = new DefaultPlanManager(getStrategy(plan));
        planScheduler = new DefaultPlanScheduler(offerAccepter);
        repairScheduler = new CassandraRepairScheduler(offerRequirementProvider,
                offerAccepter, cassandraTasks);

        // TODO: Perform reconciliation
    }

    @Override
    public void reregistered(SchedulerDriver driver,
                             Protos.MasterInfo masterInfo) {
        LOGGER.info("Re-registered with master: {}", masterInfo);
        // TODO: Perform reconciliation
    }

    @Override
    public void resourceOffers(SchedulerDriver driver,
                               List<Protos.Offer> offers) {
        logOffers(offers);
        // Perform backup operation if required

        // TODO: Add logic for handling tasks ops
        // TODO: Wait for reconciliation to finish
        List<Protos.OfferID> acceptedOffers = new ArrayList<>();

        if (planManager != null) {
            final Block currentBlock = planManager.getCurrentBlock();

            // Schedule next block from install/update plan
            acceptedOffers.addAll(
                    planScheduler.resourceOffers(driver, offers, currentBlock));

            // Perform any required repairs
            List<Protos.Offer> unacceptedOffers = filterAcceptedOffers(offers,
                    acceptedOffers);
            acceptedOffers.addAll(
                    repairScheduler.resourceOffers(
                            driver,
                            unacceptedOffers,
                            (CassandraBlock) currentBlock));

            // Schedule backup tasks
            unacceptedOffers = filterAcceptedOffers(offers,
                    acceptedOffers);
            acceptedOffers.addAll(
                    backupManager.resourceOffers(
                            driver,
                            unacceptedOffers
                    ));
        }
        declineOffers(driver, acceptedOffers, offers);
    }

    @Override
    public void offerRescinded(SchedulerDriver driver, Protos.OfferID offerId) {
        LOGGER.info("Offer rescinded. offerId: {}", offerId.getValue());
    }

    @Override
    public void statusUpdate(SchedulerDriver driver, Protos.TaskStatus status) {
        LOGGER.info(
                "Received status update for taskId={} state={} source={} reason={} message='{}'",
                status.getTaskId().getValue(),
                status.getState().toString(),
                status.getSource().name(),
                status.getReason().name(),
                status.getMessage());
        this.eventBus.post(status);
    }

    @Override
    public void frameworkMessage(SchedulerDriver driver,
                                 Protos.ExecutorID executorId
            ,
                                 Protos.SlaveID slaveId,
                                 byte[] data) {
        LOGGER.info("Framework message: executorId={} slaveId={} data='{}'",
                executorId.getValue(), slaveId.getValue(),
                Arrays.toString(data));
    }

    @Override
    public void disconnected(SchedulerDriver driver) {
        LOGGER.info("Scheduler driver disconnected.");
    }

    @Override
    public void slaveLost(SchedulerDriver driver, Protos.SlaveID slaveId) {
        LOGGER.info("Slave lost slaveId: {}", slaveId.getValue());
    }

    @Override
    public void executorLost(SchedulerDriver driver,
                             Protos.ExecutorID executorId,
                             Protos.SlaveID slaveId,
                             int status) {
        LOGGER.info("Executor lost: executorId: {} slaveId: {} status: {}",
                executorId.getValue()
                , slaveId.getValue(), status);
    }

    @Override
    public void error(SchedulerDriver driver, String message) {
        LOGGER.error("Scheduler driver error: {}", message);
    }

    // Utility methods
    private PlanStrategy getStrategy(Plan plan) {
        return new DefaultInstallStrategy(plan);
    }

    private List<Protos.Offer> filterAcceptedOffers(List<Protos.Offer> offers,
                                                    List<Protos.OfferID> acceptedOfferIds) {
        return offers.stream().filter(
                offer -> !offerAccepted(offer, acceptedOfferIds)).collect(
                Collectors.toList());
    }

    private boolean offerAccepted(Protos.Offer offer,
                                  List<Protos.OfferID> acceptedOfferIds) {
        return acceptedOfferIds.stream().anyMatch(
                acceptedOfferId -> acceptedOfferId.equals(offer.getId()));
    }

    private void registerFramework() {
        this.driver = new MesosSchedulerDriver(this,
                identityManager.get().asInfo(),
                mesosConfig.toZooKeeperUrl());
        LOGGER.info("Starting driver...");
        final Protos.Status startStatus = this.driver.start();
        LOGGER.info("Driver started with status: {}", startStatus);
    }

    private void logOffers(List<Protos.Offer> offers) {
        if (Objects.isNull(offers)) {
            return;
        }

        LOGGER.info("Received {} offers", offers.size());

        for (Protos.Offer offer : offers) {
            LOGGER.debug("Received Offer: {}", offer);
        }
    }

    private void declineOffers(SchedulerDriver driver,
                               List<Protos.OfferID> acceptedOffers,
                               List<Protos.Offer> offers) {
        for (Protos.Offer offer : offers) {
            if (!acceptedOffers.contains(offer.getId())) {
                declineOffer(driver, offer);
            }
        }
    }

    private void declineOffer(SchedulerDriver driver, Protos.Offer offer) {
        Protos.OfferID offerId = offer.getId();
        LOGGER.info("Scheduler declining offer: {}", offerId);
        driver.declineOffer(offerId);
    }
}
