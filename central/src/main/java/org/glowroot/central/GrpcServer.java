/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.central;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.grpc.internal.ServerImpl;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.Instrumentation;
import org.glowroot.central.repo.AgentDao;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.central.repo.ConfigDao;
import org.glowroot.central.repo.ConfigRepositoryImpl;
import org.glowroot.central.repo.EnvironmentDao;
import org.glowroot.central.repo.GaugeValueDao;
import org.glowroot.central.repo.HeartbeatDao;
import org.glowroot.central.repo.TraceDao;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.common.repo.util.AlertingService;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertKind;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.OldAggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.OldTransactionAggregate;
import org.glowroot.wire.api.model.CollectorServiceGrpc.CollectorServiceImplBase;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateResponseMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateStreamHeader;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateStreamMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitResponse;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent.Level;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.OldAggregateMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.OldTraceMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.OverallAggregate;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceStreamCounts;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceStreamHeader;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceStreamMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TransactionAggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.Proto;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

class GrpcServer {

    private static final Logger logger = LoggerFactory.getLogger(GrpcServer.class);

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private final AgentDao agentDao;
    private final ConfigDao configDao;
    private final EnvironmentDao environmentDao;
    private final AggregateDao aggregateDao;
    private final GaugeValueDao gaugeValueDao;
    private final HeartbeatDao heartbeatDao;
    private final TraceDao traceDao;
    private final ConfigRepositoryImpl configRepository;
    private final AlertingService alertingService;
    private final Clock clock;
    private final String version;

    private final DownstreamServiceImpl downstreamService;

    private final ServerImpl server;

    private final ExecutorService alertCheckingExecutor;

    private volatile long currentMinute;
    private final AtomicInteger nextDelay = new AtomicInteger();

    GrpcServer(String bindAddress, int port, AgentDao agentDao, ConfigDao configDao,
            AggregateDao aggregateDao, GaugeValueDao gaugeValueDao, EnvironmentDao environmentDao,
            HeartbeatDao heartbeatDao, TraceDao traceDao, ConfigRepositoryImpl configRepository,
            AlertingService alertingService, ClusterManager clusterManager, Clock clock,
            String version) throws IOException {
        this.agentDao = agentDao;
        this.configDao = configDao;
        this.environmentDao = environmentDao;
        this.aggregateDao = aggregateDao;
        this.gaugeValueDao = gaugeValueDao;
        this.heartbeatDao = heartbeatDao;
        this.traceDao = traceDao;
        this.configRepository = configRepository;
        this.alertingService = alertingService;
        this.clock = clock;
        this.version = version;

        downstreamService = new DownstreamServiceImpl(agentDao, configDao, clusterManager);

        server = NettyServerBuilder.forAddress(new InetSocketAddress(bindAddress, port))
                .addService(new CollectorServiceImpl().bindService())
                .addService(downstreamService.bindService())
                // need to override default max message size of 4mb until streaming is implemented
                // for DownstreamService.EntriesResponse and FullTraceResponse
                .maxMessageSize(64 * 1024 * 1024)
                .build()
                .start();

        alertCheckingExecutor = Executors.newSingleThreadExecutor();

        startupLogger.info("gRPC listening on {}:{}", bindAddress, port);
    }

    DownstreamServiceImpl getDownstreamService() {
        return downstreamService;
    }

    void close() {
        // shutdown server first to complete existing requests and prevent new requests
        server.shutdown();
        // then shutdown alert checking executor
        alertCheckingExecutor.shutdown();
    }

    @VisibleForTesting
    static String trimSpacesAroundAgentRollupIdSeparator(String agentRollupId) {
        return agentRollupId.replaceAll(" */ *", "/").trim();
    }

    @FunctionalInterface
    interface AlertConfigConsumer {
        void accept(AlertConfig alertConfig) throws Exception;
    }

    private class CollectorServiceImpl extends CollectorServiceImplBase {

        @Instrumentation.Transaction(transactionType = "gRPC", transactionName = "Init",
                traceHeadline = "Collect init: {{0.agentId}}", timer = "init")
        @Override
        public void collectInit(InitMessage request,
                StreamObserver<InitResponse> responseObserver) {
            String agentId = request.getAgentId();
            AgentConfig updatedAgentConfig;
            try {
                String agentRollupId = request.getAgentRollupId();
                // trim spaces around rollup separator "/"
                agentRollupId = trimSpacesAroundAgentRollupIdSeparator(agentRollupId);
                updatedAgentConfig = configDao.store(agentId, Strings.emptyToNull(agentRollupId),
                        request.getAgentConfig());
                environmentDao.insert(agentId, request.getEnvironment());
                // insert into agent_rollup last so environment and agent config will return
                // non-null if the agent is visible in the UI dropdown
                agentDao.store(agentId, Strings.emptyToNull(agentRollupId));
            } catch (Throwable t) {
                logger.error("{} - {}", getDisplayForLogging(agentId), t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
            logger.info("agent connected: {}, version {}", getDisplayForLogging(agentId),
                    request.getEnvironment().getJavaInfo().getGlowrootAgentVersion());
            InitResponse.Builder response = InitResponse.newBuilder()
                    .setGlowrootCentralVersion(version);
            if (!updatedAgentConfig.equals(request.getAgentConfig())) {
                response.setAgentConfig(updatedAgentConfig);
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        }

        @Override
        public StreamObserver<AggregateStreamMessage> collectAggregateStream(
                final StreamObserver<AggregateResponseMessage> responseObserver) {
            return new StreamObserver<AggregateStreamMessage>() {

                private @MonotonicNonNull AggregateStreamHeader streamHeader;
                private List<Aggregate.SharedQueryText> sharedQueryTexts = Lists.newArrayList();
                private Map<String, OldAggregatesByType.Builder> aggregatesByTypeMap =
                        Maps.newHashMap();

                @Override
                public void onNext(AggregateStreamMessage value) {
                    switch (value.getMessageCase()) {
                        case STREAM_HEADER:
                            streamHeader = value.getStreamHeader();
                            break;
                        case SHARED_QUERY_TEXT:
                            sharedQueryTexts.add(value.getSharedQueryText());
                            break;
                        case OVERALL_AGGREGATE:
                            OverallAggregate overallAggregate = value.getOverallAggregate();
                            String transactionType = overallAggregate.getTransactionType();
                            aggregatesByTypeMap.put(transactionType,
                                    OldAggregatesByType.newBuilder()
                                            .setTransactionType(transactionType)
                                            .setOverallAggregate(overallAggregate.getAggregate()));
                            break;
                        case TRANSACTION_AGGREGATE:
                            TransactionAggregate transactionAggregate =
                                    value.getTransactionAggregate();
                            OldAggregatesByType.Builder builder = checkNotNull(aggregatesByTypeMap
                                    .get(transactionAggregate.getTransactionType()));
                            builder.addTransactionAggregate(OldTransactionAggregate.newBuilder()
                                    .setTransactionName(transactionAggregate.getTransactionName())
                                    .setAggregate(transactionAggregate.getAggregate())
                                    .build());
                            break;
                        default:
                            throw new RuntimeException(
                                    "Unexpected message: " + value.getMessageCase());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    if (streamHeader == null) {
                        logger.error(t.getMessage(), t);
                    } else {
                        logger.error("{} - {}", getDisplayForLogging(streamHeader.getAgentId()),
                                t.getMessage(), t);
                    }
                }

                @Instrumentation.Transaction(transactionType = "gRPC",
                        transactionName = "Aggregates",
                        traceHeadline = "Collect aggregates: {{this.header.agentId}}",
                        timer = "aggregates")
                @Override
                public void onCompleted() {
                    checkNotNull(streamHeader);
                    List<OldAggregatesByType> aggregatesByTypeList = Lists.newArrayList();
                    for (OldAggregatesByType.Builder aggregatesByType : aggregatesByTypeMap
                            .values()) {
                        aggregatesByTypeList.add(aggregatesByType.build());
                    }
                    collectAggregatesInternal(streamHeader.getAgentId(),
                            streamHeader.getCaptureTime(),
                            sharedQueryTexts, aggregatesByTypeList, responseObserver);
                }
            };
        }

        @Instrumentation.Transaction(transactionType = "gRPC", transactionName = "Aggregates",
                traceHeadline = "Collect aggregates: {{0.agentId}}", timer = "aggregates")
        @Override
        public void collectAggregates(OldAggregateMessage request,
                StreamObserver<AggregateResponseMessage> responseObserver) {

            List<Aggregate.SharedQueryText> sharedQueryTexts;
            List<String> oldSharedQueryTexts = request.getOldSharedQueryTextList();
            if (oldSharedQueryTexts.isEmpty()) {
                sharedQueryTexts = request.getSharedQueryTextList();
            } else {
                // handle agents prior to 0.9.3
                sharedQueryTexts = Lists.newArrayList();
                for (String oldSharedQueryText : oldSharedQueryTexts) {
                    sharedQueryTexts.add(Aggregate.SharedQueryText.newBuilder()
                            .setFullText(oldSharedQueryText)
                            .build());
                }
            }
            collectAggregatesInternal(request.getAgentId(), request.getCaptureTime(),
                    sharedQueryTexts, request.getAggregatesByTypeList(), responseObserver);
        }

        private void collectAggregatesInternal(String agentId, long captureTime,
                List<Aggregate.SharedQueryText> sharedQueryTexts,
                List<OldAggregatesByType> aggregatesByTypeList,
                StreamObserver<AggregateResponseMessage> responseObserver) {
            if (!aggregatesByTypeList.isEmpty()) {
                try {
                    aggregateDao.store(agentId, captureTime, aggregatesByTypeList,
                            sharedQueryTexts);
                } catch (Throwable t) {
                    logger.error("{} - {}", getDisplayForLogging(agentId), t.getMessage(), t);
                    responseObserver.onError(t);
                    return;
                }
            }
            String agentDisplay;
            try {
                agentDisplay = agentDao.readAgentRollupDisplay(agentId);
            } catch (Exception e) {
                logger.error("{} - {}", getDisplayForLogging(agentId), e.getMessage(), e);
                responseObserver.onError(e);
                return;
            }
            checkAlerts(agentId, agentDisplay, AlertKind.TRANSACTION,
                    alertConfig -> checkTransactionAlert(agentId, agentDisplay, alertConfig,
                            captureTime));
            responseObserver.onNext(AggregateResponseMessage.newBuilder()
                    .setNextDelayMillis(getNextDelayMillis())
                    .build());
            responseObserver.onCompleted();
        }

        private int getNextDelayMillis() {
            long currentishTimeMillis = clock.currentTimeMillis() + 10000;
            if (currentishTimeMillis > currentMinute) {
                // race condition here is ok, at worst results in resetting nextDelay multiple times
                nextDelay.set(0);
                currentMinute = (long) Math.ceil(currentishTimeMillis / 60000.0) * 60000;
            }
            // spread out aggregate collections 100 milliseconds a part, rolling over at 10 seconds
            return nextDelay.getAndAdd(100) % 10000;
        }

        @Instrumentation.Transaction(transactionType = "gRPC", transactionName = "Gauges",
                traceHeadline = "Collect gauge values: {{0.agentId}}", timer = "gauges")
        @Override
        public void collectGaugeValues(GaugeValueMessage request,
                StreamObserver<EmptyMessage> responseObserver) {
            String agentId = request.getAgentId();
            long maxCaptureTime = 0;
            try {
                gaugeValueDao.store(agentId, request.getGaugeValuesList());
                for (GaugeValue gaugeValue : request.getGaugeValuesList()) {
                    maxCaptureTime = Math.max(maxCaptureTime, gaugeValue.getCaptureTime());
                }
            } catch (Throwable t) {
                logger.error("{} - {}", getDisplayForLogging(agentId), t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
            try {
                heartbeatDao.store(agentId);
            } catch (Throwable t) {
                logger.error("{} - {}", getDisplayForLogging(agentId), t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
            String agentDisplay;
            try {
                agentDisplay = agentDao.readAgentRollupDisplay(agentId);
            } catch (Throwable t) {
                logger.error("{} - {}", getDisplayForLogging(agentId), t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
            final long captureTime = maxCaptureTime;
            checkAlerts(agentId, agentDisplay, AlertKind.GAUGE,
                    alertConfig -> checkGaugeAlert(agentId, agentDisplay, alertConfig,
                            captureTime));
            checkAlerts(agentId, agentDisplay, AlertKind.HEARTBEAT,
                    alertConfig -> checkHeartbeatAlert(agentId, agentDisplay, alertConfig));
            responseObserver.onNext(EmptyMessage.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public StreamObserver<TraceStreamMessage> collectTraceStream(
                final StreamObserver<EmptyMessage> responseObserver) {
            return new StreamObserver<TraceStreamMessage>() {

                private @MonotonicNonNull TraceStreamHeader streamHeader;
                private List<Trace.SharedQueryText> sharedQueryTexts = Lists.newArrayList();
                private @MonotonicNonNull Trace trace;
                private List<Trace.Entry> entries = Lists.newArrayList();
                private @MonotonicNonNull Profile mainThreadProfile;
                private @MonotonicNonNull Profile auxThreadProfile;
                // TODO report checker framework issue that occurs with normal annotation placement
                private Trace./*@MonotonicNonNull*/Header header;
                private @MonotonicNonNull TraceStreamCounts streamCounts;

                @Override
                public void onNext(TraceStreamMessage value) {
                    switch (value.getMessageCase()) {
                        case STREAM_HEADER:
                            streamHeader = value.getStreamHeader();
                            break;
                        case SHARED_QUERY_TEXT:
                            sharedQueryTexts.add(value.getSharedQueryText());
                            break;
                        case TRACE:
                            // this is for 0.9.12 and prior agents
                            trace = value.getTrace();
                            break;
                        case ENTRY:
                            entries.add(value.getEntry());
                            break;
                        case MAIN_THREAD_PROFILE:
                            mainThreadProfile = value.getMainThreadProfile();
                            break;
                        case AUX_THREAD_PROFILE:
                            auxThreadProfile = value.getAuxThreadProfile();
                            break;
                        case HEADER:
                            header = value.getHeader();
                            break;
                        case STREAM_COUNTS:
                            streamCounts = value.getStreamCounts();
                            break;
                        default:
                            throw new RuntimeException(
                                    "Unexpected message: " + value.getMessageCase());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    if (streamHeader == null) {
                        logger.error(t.getMessage(), t);
                    } else {
                        logger.error("{} - {}", getDisplayForLogging(streamHeader.getAgentId()),
                                t.getMessage(), t);
                    }
                }

                @Instrumentation.Transaction(transactionType = "gRPC", transactionName = "Trace",
                        traceHeadline = "Collect trace: {{this.header.agentId}}", timer = "trace")
                @Override
                public void onCompleted() {
                    checkNotNull(streamHeader);
                    if (trace == null) {
                        checkNotNull(streamCounts);
                        // this is for 0.9.13 and later agents
                        if (!isEverythingReceived()) {
                            // no point in calling onError to force re-try since gRPC maxMessageSize
                            // limit will just be hit again
                            responseObserver.onNext(EmptyMessage.getDefaultInstance());
                            responseObserver.onCompleted();
                        }
                        Trace.Builder builder = Trace.newBuilder()
                                .setId(streamHeader.getTraceId())
                                .setUpdate(streamHeader.getUpdate())
                                .setHeader(checkNotNull(header))
                                .addAllEntry(entries)
                                .addAllSharedQueryText(sharedQueryTexts);
                        if (mainThreadProfile != null) {
                            builder.setMainThreadProfile(mainThreadProfile);
                        }
                        if (auxThreadProfile != null) {
                            builder.setAuxThreadProfile(auxThreadProfile);
                        }
                        trace = builder.build();
                    } else {
                        trace = trace.toBuilder()
                                .addAllSharedQueryText(sharedQueryTexts)
                                .build();
                    }
                    try {
                        traceDao.store(streamHeader.getAgentId(), trace);
                    } catch (Throwable t) {
                        logger.error("{} - {}", getDisplayForLogging(streamHeader.getAgentId()),
                                t.getMessage(), t);
                        responseObserver.onError(t);
                        return;
                    }
                    responseObserver.onNext(EmptyMessage.getDefaultInstance());
                    responseObserver.onCompleted();
                }

                @RequiresNonNull({"streamHeader", "streamCounts"})
                private boolean isEverythingReceived() {
                    // validate that all data was received, may not receive everything due to gRPC
                    // maxMessageSize limit exceeded: "Compressed frame exceeds maximum frame size"
                    if (header == null) {
                        logger.error("{} - did not receive header, likely due to gRPC"
                                + " maxMessageSize limit exceeded",
                                getDisplayForLogging(streamHeader.getAgentId()));
                        return false;
                    }
                    if (sharedQueryTexts.size() < streamCounts.getSharedQueryTextCount()) {
                        logger.error("{} - expected {} shared query texts, but only received {},"
                                + " likely due to gRPC maxMessageSize limit exceeded for some of"
                                + " them", getDisplayForLogging(streamHeader.getAgentId()),
                                streamCounts.getSharedQueryTextCount(), sharedQueryTexts.size());
                        return false;
                    }
                    if (entries.size() < streamCounts.getEntryCount()) {
                        logger.error("{} - expected {} entries, but only received {},"
                                + " likely due to gRPC maxMessageSize limit exceeded for some of"
                                + " them", getDisplayForLogging(streamHeader.getAgentId()),
                                streamCounts.getEntryCount(), entries.size());
                        return false;
                    }
                    checkState(sharedQueryTexts.size() == streamCounts.getSharedQueryTextCount());
                    checkState(entries.size() == streamCounts.getEntryCount());
                    return true;
                }
            };
        }

        @Instrumentation.Transaction(transactionType = "gRPC", transactionName = "Trace",
                traceHeadline = "Collect trace: {{0.agentId}}", timer = "trace")
        @Override
        public void collectTrace(OldTraceMessage request,
                StreamObserver<EmptyMessage> responseObserver) {
            String agentId = request.getAgentId();
            try {
                traceDao.store(agentId, request.getTrace());
            } catch (Throwable t) {
                logger.error("{} - {}", getDisplayForLogging(agentId), t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
            responseObserver.onNext(EmptyMessage.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Instrumentation.Transaction(transactionType = "gRPC", transactionName = "Log",
                traceHeadline = "Log: {{0.agentId}}", timer = "log")
        @Override
        public void log(LogMessage request, StreamObserver<EmptyMessage> responseObserver) {
            String agentId = request.getAgentId();
            try {
                LogEvent logEvent = request.getLogEvent();
                Level level = logEvent.getLevel();
                String agentDisplay = agentDao.readAgentRollupDisplay(agentId);
                if (logEvent.hasThrowable()) {
                    log(level, "{} -- {} -- {} -- {}\n{}", agentDisplay, level,
                            logEvent.getLoggerName(), logEvent.getMessage(),
                            toString(logEvent.getThrowable()));
                } else {
                    log(level, "{} -- {} -- {} -- {}", agentDisplay, level,
                            logEvent.getLoggerName(), logEvent.getMessage());
                }
            } catch (Throwable t) {
                logger.error("{} - {}", getDisplayForLogging(agentId), t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
            responseObserver.onNext(EmptyMessage.getDefaultInstance());
            responseObserver.onCompleted();
        }

        private void checkAlerts(String agentId, String agentDisplay, AlertKind alertKind,
                AlertConfigConsumer check) {
            List<AlertConfig> alertConfigs;
            try {
                alertConfigs = configRepository.getAlertConfigs(agentId, alertKind);
            } catch (Exception e) {
                logger.error("{} - {}", agentDisplay, e.getMessage(), e);
                return;
            }
            if (alertConfigs.isEmpty()) {
                return;
            }
            alertCheckingExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        runInternal();
                    } catch (Throwable t) {
                        logger.error("{} - {}", agentDisplay, t.getMessage(), t);
                    }
                }
                private void runInternal() throws InterruptedException {
                    for (AlertConfig alertConfig : alertConfigs) {
                        try {
                            check.accept(alertConfig);
                        } catch (InterruptedException e) {
                            // shutdown requested
                            throw e;
                        } catch (Exception e) {
                            logger.error("{} - {}", agentDisplay, e.getMessage(), e);
                        }
                    }
                }
            });
        }

        @Instrumentation.Transaction(transactionType = "Background",
                transactionName = "Check transaction alert",
                traceHeadline = "Check transaction alert: {{0}}", timer = "check transaction alert")
        private void checkTransactionAlert(String agentId, String agentDisplay,
                AlertConfig alertConfig, long endTime) throws Exception {
            alertingService.checkTransactionAlert(agentId, agentDisplay, alertConfig, endTime);
        }

        @Instrumentation.Transaction(transactionType = "Background",
                transactionName = "Check gauge alert",
                traceHeadline = "Check gauge alert: {{0}}", timer = "check gauge alert")
        private void checkGaugeAlert(String agentId, String agentDisplay, AlertConfig alertConfig,
                long endTime) throws Exception {
            alertingService.checkGaugeAlert(agentId, agentDisplay, alertConfig, endTime);
        }

        @Instrumentation.Transaction(transactionType = "Background",
                transactionName = "Check heartbeat alert",
                traceHeadline = "Check heartbeat alert: {{0}}", timer = "check heartbeat alert")
        private void checkHeartbeatAlert(String agentId, String agentDisplay,
                AlertConfig alertConfig) throws Exception {
            alertingService.sendHeartbeatAlertIfNeeded(agentId, agentDisplay, alertConfig, false);
        }

        private String getDisplayForLogging(String agentRollupId) {
            try {
                return agentDao.readAgentRollupDisplay(agentRollupId);
            } catch (Exception e) {
                logger.error("{} - {}", agentRollupId, e.getMessage(), e);
                return "id:" + agentRollupId;
            }
        }

        private String toString(Proto.Throwable t) {
            StringBuilder sb = new StringBuilder();
            sb.append(t.getClassName());
            String message = t.getMessage();
            if (!message.isEmpty()) {
                sb.append(": ");
                sb.append(message);
            }
            for (Proto.StackTraceElement stackTraceElement : t.getStackTraceElementList()) {
                sb.append("\n\tat ");
                sb.append(new StackTraceElement(stackTraceElement.getClassName(),
                        stackTraceElement.getMethodName(), stackTraceElement.getFileName(),
                        stackTraceElement.getLineNumber()));
            }
            int framesInCommonWithEnclosing = t.getFramesInCommonWithEnclosing();
            if (framesInCommonWithEnclosing > 0) {
                sb.append("\n\t... ");
                sb.append(framesInCommonWithEnclosing);
                sb.append(" more");
            }
            if (t.hasCause()) {
                sb.append("\nCaused by: ");
                sb.append(toString(t.getCause()));
            }
            return sb.toString();
        }

        private void log(Level level, String format, Object... arguments) {
            switch (level) {
                case ERROR:
                    logger.error(format, arguments);
                    break;
                case WARN:
                    logger.warn(format, arguments);
                    break;
                default:
                    logger.info(format, arguments);
                    break;
            }
        }
    }
}
