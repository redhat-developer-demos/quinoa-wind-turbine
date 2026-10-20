//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.apache.kafka:kafka-clients:4.0.0

// Creates the game's Kafka topics on a cluster that doesn't auto-create them, for the self-managed
// Confluent Platform path (Confluent Cloud uses scripts/confluent-provision.sh and the Cloud CLI
// instead). It authenticates with SASL_SSL/PLAIN and trusts a PEM CA, so it works against a broker
// with a private CA. kafka-clients 4.x is required: older clients call the removed Security Manager
// (Subject.getSubject) and fail on Java 24+.
//
// Usage (env vars):
//   BOOTSTRAP=host:9094,host:9095,host:9096 SASL_USER=... SASL_PW=... CA=/path/to/kafka-ca.crt \
//     jbang scripts/CreateTopics.java
//
// Partitions and retention match the Strimzi manifest: one partition each, replication 3,
// race-progress compacted, race-commentary and race-modifiers kept for an hour.
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.errors.TopicExistsException;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class CreateTopics {
  public static void main(String[] a) throws Exception {
    String bootstrap = require("BOOTSTRAP");
    Properties p = new Properties();
    p.put("bootstrap.servers", bootstrap);
    p.put("security.protocol", "SASL_SSL");
    p.put("sasl.mechanism", "PLAIN");
    p.put("sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
        + require("SASL_USER") + "\" password=\"" + require("SASL_PW") + "\";");
    String ca = System.getenv("CA");
    if (ca != null && !ca.isBlank()) {
      p.put("ssl.truststore.location", ca);
      p.put("ssl.truststore.type", "PEM");
    }
    List<NewTopic> topics = List.of(
      new NewTopic("power", 1, (short) 3),
      new NewTopic("game-events", 1, (short) 3),
      new NewTopic("race-progress", 1, (short) 3).configs(Map.of("cleanup.policy", "compact")),
      new NewTopic("race-commentary", 1, (short) 3).configs(Map.of("retention.ms", "3600000")),
      new NewTopic("race-modifiers", 1, (short) 3).configs(Map.of("retention.ms", "3600000"))
    );
    try (Admin admin = Admin.create(p)) {
      for (NewTopic t : topics) {
        try {
          admin.createTopics(List.of(t)).all().get();
          System.out.println("created " + t.name());
        } catch (ExecutionException e) {
          if (e.getCause() instanceof TopicExistsException) {
            System.out.println("exists  " + t.name());
          } else {
            System.out.println("FAILED  " + t.name() + ": " + e.getCause().getMessage());
          }
        }
      }
    }
  }

  private static String require(String name) {
    String v = System.getenv(name);
    if (v == null || v.isBlank()) {
      throw new IllegalStateException("Missing environment variable " + name);
    }
    return v;
  }
}
