package com.alarm.eagle;

import com.alarm.eagle.config.ConfigConstant;
import com.alarm.eagle.config.EagleProperties;
import com.alarm.eagle.es.ElasticsearchUtil;
import com.alarm.eagle.es.EsActionRequestFailureHandler;
import com.alarm.eagle.es.EsSinkFunction;
import com.alarm.eagle.log.CountTriggerWithTimeout;
import com.alarm.eagle.log.LogEntry;
import com.alarm.eagle.log.LogProcessFunction;
import com.alarm.eagle.log.LogSchema;
import com.alarm.eagle.redis.*;
import com.alarm.eagle.rule.RuleBase;
import com.alarm.eagle.rule.RuleSourceFunction;
import com.alarm.eagle.util.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.shaded.guava18.com.google.common.collect.Lists;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.BroadcastConnectedStream;
import org.apache.flink.streaming.api.datastream.BroadcastStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.WindowedStream;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchSinkBase;
import org.apache.flink.streaming.connectors.elasticsearch6.ElasticsearchSink;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer010;
import org.apache.flink.util.Collector;
import org.apache.http.HttpHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Created by luxiaoxun on 2020/01/27.
 */
public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    // a map descriptor to store the rules
    private static final MapStateDescriptor<String, RuleBase> ruleStateDescriptor = new MapStateDescriptor<>(
            "rules-state", BasicTypeInfo.STRING_TYPE_INFO, TypeInformation.of(new TypeHint<RuleBase>() {
    }));

    public static void main(String[] args) {
        try {
            ParameterTool params = ParameterTool.fromArgs(args);
            ParameterTool parameter = EagleProperties.getInstance(params).getParameter();
            showConf(parameter);

            // Build stream DAG
            StreamExecutionEnvironment env = getStreamExecutionEnvironment(parameter);
            DataStream<LogEntry> dataSource = getKafkaDataSource(parameter, env);
            BroadcastStream<RuleBase> ruleSource = getRuleDataSource(parameter, env);
            DataStream<LogEntry> processedSource = processLogStream(parameter, dataSource, ruleSource);
            sinkToRedis(parameter, processedSource);
            sinkToElasticsearch(parameter, processedSource);

            env.getConfig().setGlobalJobParameters(parameter);
            env.execute("eagle-log");
        } catch (Exception e) {
            logger.error(e.toString());
        }
    }

    private static StreamExecutionEnvironment getStreamExecutionEnvironment(ParameterTool parameter) {
        StreamExecutionEnvironment env = null;
        int globalParallelism = parameter.getInt(ConfigConstant.FLINK_PARALLELISM);
        if (parameter.get(ConfigConstant.FLINK_MODE).equals(ConfigConstant.MODE_DEV)) {
            env = StreamExecutionEnvironment.createLocalEnvironment();
            globalParallelism = 1;
        } else {
            env = StreamExecutionEnvironment.getExecutionEnvironment();
        }
        env.setParallelism(globalParallelism);

        //checkpoint
        boolean enableCheckpoint = parameter.getBoolean(ConfigConstant.FLINK_ENABLE_CHECKPOINT, false);
        if (enableCheckpoint) {
            env.enableCheckpointing(60000L);
            CheckpointConfig config = env.getCheckpointConfig();
            config.setMinPauseBetweenCheckpoints(30000L);
            config.setCheckpointTimeout(10000L);
            //RETAIN_ON_CANCELLATION则在job cancel的时候会保留externalized checkpoint state
            config.enableExternalizedCheckpoints(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
        }

        return env;
    }

    private static BroadcastStream<RuleBase> getRuleDataSource(ParameterTool parameter, StreamExecutionEnvironment env) {
        String ruleUrl = parameter.get(ConfigConstant.STREAM_RULE_URL);
        String ruleName = "rules-source";
        return env.addSource(new RuleSourceFunction(ruleUrl)).name(ruleName).uid(ruleName).setParallelism(1)
                .broadcast(ruleStateDescriptor);
    }

    private static DataStream<LogEntry> getKafkaDataSource(ParameterTool parameter, StreamExecutionEnvironment env) {
        String kafkaBootstrapServers = parameter.get(ConfigConstant.KAFKA_BOOTSTRAP_SERVERS);
        String kafkaGroupId = parameter.get(ConfigConstant.KAFKA_GROUP_ID);
        String kafkaTopic = parameter.get(ConfigConstant.KAFKA_TOPIC);
        int kafkaParallelism = parameter.getInt(ConfigConstant.KAFKA_TOPIC_PARALLELISM);

        Properties properties = new Properties();
        properties.setProperty("bootstrap.servers", kafkaBootstrapServers);
        properties.setProperty("group.id", kafkaGroupId);
        FlinkKafkaConsumer010<LogEntry> source = new FlinkKafkaConsumer010<>(kafkaTopic, new LogSchema(), properties);

        return env.addSource(source).name(kafkaTopic).uid(kafkaTopic).setParallelism(kafkaParallelism);
    }

    private static DataStream<LogEntry> processLogStream(ParameterTool parameter, DataStream<LogEntry> dataSource,
                                                         BroadcastStream<RuleBase> ruleSource) throws Exception {
        BroadcastConnectedStream<LogEntry, RuleBase> connectedStreams = dataSource.connect(ruleSource);
        int processParallelism = parameter.getInt(ConfigConstant.STREAM_PROCESS_PARALLELISM);
        RuleBase ruleBase = getInitRuleBase(parameter);
        if (ruleBase == null) {
            throw new Exception("Can not init rules base");
        } else {
            String name = "process-log";
            logger.debug("Init rules: " + ruleBase.toString());
            return connectedStreams.process(new LogProcessFunction(ruleStateDescriptor, ruleBase))
                    .setParallelism(processParallelism).name(name).uid(name);
        }
    }

    private static RuleBase getInitRuleBase(ParameterTool parameter) {
        String ruleUrl = parameter.get(ConfigConstant.STREAM_RULE_URL);
//        String content = HttpUtil.doGet(ruleUrl);
        String content = HttpUtil.doGetMock(ruleUrl);
        if (content == null) {
            logger.error("Failed to get rules from url {}", ruleUrl);
            return null;
        }

        JSONArray resJson = JSON.parseArray(content);
        if (resJson == null) {
            logger.error("Failed to parse json:{}", content);
            return null;
        }

        RuleBase ruleBase = RuleBase.createRuleBase(resJson);
        return ruleBase;
    }

    private static void sinkToElasticsearch(ParameterTool parameter, DataStream<LogEntry> dataSource) {
        List<HttpHost> esHttpHosts = ElasticsearchUtil.getEsAddresses(parameter.get(ConfigConstant.ELASTICSEARCH_HOSTS));
        int bulkMaxActions = parameter.getInt(ConfigConstant.ELASTICSEARCH_BULK_FLUSH_MAX_ACTIONS, 5000);
        int bulkMaxSize = parameter.getInt(ConfigConstant.ELASTICSEARCH_BULK_FLUSH_MAX_SIZE_MB, 5);
        int intervalMillis = parameter.getInt(ConfigConstant.ELASTICSEARCH_BULK_FLUSH_INTERVAL_MS, 1000);
        int esSinkParallelism = parameter.getInt(ConfigConstant.ELASTICSEARCH_SINK_PARALLELISM);
        String indexPostfix = parameter.get(ConfigConstant.ELASTICSEARCH_INDEX_POSTFIX, "");

        String name = "ES-sink";
        ElasticsearchSink.Builder<LogEntry> esSinkBuilder = new ElasticsearchSink.Builder<>(esHttpHosts, new EsSinkFunction(indexPostfix));
        esSinkBuilder.setBulkFlushMaxActions(bulkMaxActions);
        esSinkBuilder.setBulkFlushMaxSizeMb(bulkMaxSize);
        esSinkBuilder.setBulkFlushInterval(intervalMillis);
        esSinkBuilder.setBulkFlushBackoff(true);
        esSinkBuilder.setBulkFlushBackoffRetries(3);
        esSinkBuilder.setBulkFlushBackoffType(ElasticsearchSinkBase.FlushBackoffType.CONSTANT);
        esSinkBuilder.setBulkFlushBackoffDelay(1000);
        esSinkBuilder.setFailureHandler(new EsActionRequestFailureHandler());
        dataSource.addSink(esSinkBuilder.build()).setParallelism(esSinkParallelism).name(name).uid(name);
    }

    private static void sinkToRedis(ParameterTool parameter, DataStream<LogEntry> dataSource) {
        // save statistic information in redis
        int windowTime = parameter.getInt(ConfigConstant.REDIS_WINDOW_TIME_SECONDS);
        int windowCount = parameter.getInt(ConfigConstant.REDIS_WINDOW_TRIGGER_COUNT);
        int redisSinkParallelism = parameter.getInt(ConfigConstant.REDIS_SINK_PARALLELISM);
        String name = "redis-agg-log";
//        DataStream<Tuple2<String, List<LogEntry>>> keyedStream = dataSource.keyBy((KeySelector<LogEntry, String>) log -> log.getIndex())
//                .timeWindow(Time.seconds(windowTime)).trigger(new CountTriggerWithTimeout<>(windowCount, TimeCharacteristic.ProcessingTime))
//                .process(new ProcessWindowFunction<LogEntry, Tuple2<String, List<LogEntry>>, String, TimeWindow>() {
//                    @Override
//                    public void process(String s, Context context, Iterable<LogEntry> iterable, Collector<Tuple2<String, List<LogEntry>>> collector) throws Exception {
//                        ArrayList<LogEntry> logs = Lists.newArrayList(iterable);
//                        if (logs.size() > 0) {
//                            collector.collect(new Tuple2(s, logs));
//                        }
//                    }
//                }).setParallelism(redisSinkParallelism).name(name).uid(name);
        DataStream<LogStatWindowResult> keyedStream = dataSource.keyBy((KeySelector<LogEntry, String>) log -> log.getIndex())
                .timeWindow(Time.seconds(windowTime))
                .trigger(new CountTriggerWithTimeout<>(windowCount, TimeCharacteristic.ProcessingTime))
                .aggregate(new LogStatAggregateFunction(), new LogStatWindowFunction())
                .setParallelism(redisSinkParallelism).name(name).uid(name);
        String sinkName = "redis-sink";
        keyedStream.addSink(new RedisAggSinkFunction()).setParallelism(redisSinkParallelism).name(sinkName).uid(sinkName);
    }

    private static void showConf(ParameterTool parameter) {
        logger.info("Show " + parameter.getNumberOfParameters() + " config parameters");
        Configuration configuration = parameter.getConfiguration();
        Map<String, String> map = configuration.toMap();
        for (String key : map.keySet()) {
            logger.info(key + ":" + map.get(key));
        }
    }
}
