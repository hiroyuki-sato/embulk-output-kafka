package org.embulk.output.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigException;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.spi.Exec;
import org.embulk.spi.OutputPlugin;
import org.embulk.spi.PageReader;
import org.embulk.spi.Schema;
import org.embulk.spi.TransactionalPageOutput;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class KafkaOutputPlugin
        implements OutputPlugin
{
    public enum RecordSerializeFormat
    {
        JSON,
        AVRO_WITH_SCHEMA_REGISTRY;

        @JsonValue
        public String toString()
        {
            return name().toLowerCase(Locale.ENGLISH);
        }

        @JsonCreator
        public static RecordSerializeFormat ofString(String name)
        {
            switch (name.toLowerCase(Locale.ENGLISH)) {
                case "json":
                    return JSON;
                case "avro_with_schema_registry":
                    return AVRO_WITH_SCHEMA_REGISTRY;
                default:
            }

            throw new ConfigException(String.format("Unknown serialize format '%s'. Supported modes are json, avro_with_schema_registry", name));
        }
    }

    public interface PluginTask
            extends Task
    {
        @Config("brokers")
        public List<String> getBrokers();

        @Config("topic")
        public String getTopic();

        @Config("topic_column")
        @ConfigDefault("null")
        public Optional<String> getTopicColumn();

        @Config("schema_registry_url")
        @ConfigDefault("null")
        public Optional<String> getSchemaRegistryUrl();

        @Config("serialize_format")
        public RecordSerializeFormat getRecordSerializeFormat();

        @Config("avsc_file")
        @ConfigDefault("null")
        public Optional<File> getAvscFile();

        @Config("avsc")
        @ConfigDefault("null")
        public Optional<ObjectNode> getAvsc();

        @Config("key_column_name")
        @ConfigDefault("null")
        public Optional<String> getKeyColumnName();

        @Config("partition_column_name")
        @ConfigDefault("null")
        public Optional<String> getPartitionColumnName();

        @Config("record_batch_size")
        @ConfigDefault("16384")
        public int getRecordBatchSize();

        @Config("acks")
        @ConfigDefault("\"1\"")
        public String getAcks();

        @Config("retries")
        @ConfigDefault("1")
        public int getRetries();

        @Config("other_producer_configs")
        @ConfigDefault("{}")
        public Map<String, String> getOtherProducerConfigs();

        @Config("ignore_columns")
        @ConfigDefault("[]")
        public List<String> getIgnoreColumns();

        @Config("value_subject_name_strategy")
        @ConfigDefault("null")
        public Optional<String> getValueSubjectNameStrategy();
    }

    private static ObjectMapper objectMapper = new ObjectMapper();

    private AdminClient getKafkaAdminClient(PluginTask task)
    {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, task.getBrokers());
        return AdminClient.create(properties);
    }

    @Override
    public ConfigDiff transaction(ConfigSource config,
            Schema schema, int taskCount,
            Control control)
    {
        PluginTask task = config.loadConfig(PluginTask.class);
        AdminClient adminClient = getKafkaAdminClient(task);
        DescribeTopicsResult result = adminClient.describeTopics(ImmutableList.of(task.getTopic()));
        try {
            if (result.all().get(30, TimeUnit.SECONDS).size() == 0) {
                throw new RuntimeException("target topic is not found");
            }
        }
        catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException("failed to connect kafka brokers");
        }

        control.run(task.dump());
        return Exec.newConfigDiff();
    }

    @Override
    public ConfigDiff resume(TaskSource taskSource,
            Schema schema, int taskCount,
            Control control)
    {
        throw new UnsupportedOperationException("kafka output plugin does not support resuming");
    }

    @Override
    public void cleanup(TaskSource taskSource,
            Schema schema, int taskCount,
            List<TaskReport> successTaskReports)
    {
    }

    @Override
    public TransactionalPageOutput open(TaskSource taskSource, Schema schema, int taskIndex)
    {
        PluginTask task = taskSource.loadTask(PluginTask.class);

        switch (task.getRecordSerializeFormat()) {
            case JSON:
                return buildPageOutputForJson(task, schema, taskIndex);
            case AVRO_WITH_SCHEMA_REGISTRY:
                return buildPageOutputForAvroWithSchemaRegistry(task, schema, taskIndex);
            default:
                throw new ConfigException("Unknown serialize format");
        }
    }

    private TransactionalPageOutput buildPageOutputForJson(PluginTask task, Schema schema, int taskIndex)
    {
        KafkaProducer<Object, ObjectNode> producer = RecordProducerFactory.getForJson(task, schema, task.getOtherProducerConfigs());
        PageReader pageReader = new PageReader(schema);
        KafkaOutputColumnVisitor<ObjectNode> columnVisitor = new JsonFormatColumnVisitor(task, pageReader, objectMapper);

        return new JsonFormatTransactionalPageOutput(producer, pageReader, columnVisitor, task.getTopic(), taskIndex);
    }

    private TransactionalPageOutput buildPageOutputForAvroWithSchemaRegistry(PluginTask task, Schema schema, int taskIndex)
    {
        KafkaProducer<Object, Object> producer = RecordProducerFactory.getForAvroWithSchemaRegistry(task, schema, task.getOtherProducerConfigs());
        PageReader pageReader = new PageReader(schema);
        org.apache.avro.Schema avroSchema = getAvroSchema(task);
        AvroFormatColumnVisitor avroFormatColumnVisitor = new AvroFormatColumnVisitor(task, pageReader, avroSchema);

        return new AvroFormatTransactionalPageOutput(producer, pageReader, avroFormatColumnVisitor, task.getTopic(), taskIndex);
    }

    private org.apache.avro.Schema getAvroSchema(PluginTask task)
    {
        org.apache.avro.Schema avroSchema = null;
        if ((!task.getAvsc().isPresent() && !task.getAvscFile().isPresent()) || (task.getAvsc().isPresent() && task.getAvscFile().isPresent())) {
            throw new ConfigException("avro_with_schema_registry format needs either one of avsc and avsc_file");
        }
        if (task.getAvsc().isPresent()) {
            avroSchema = new org.apache.avro.Schema.Parser().parse(task.getAvsc().get().toString());
        }
        if (task.getAvscFile().isPresent()) {
            try {
                avroSchema = new org.apache.avro.Schema.Parser().parse(task.getAvscFile().get());
            }
            catch (IOException e) {
                e.printStackTrace();
                throw new ConfigException("avsc_file cannot read");
            }
        }

        return avroSchema;
    }
}
