package io.nwdaf.eventsubscription;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.metrics.JvmMetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.LogbackMetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.nwdaf.eventsubscription.config.NwdafSubProperties;
import io.nwdaf.eventsubscription.datacollection.dummy.DummyDataProducerPublisher;
import io.nwdaf.eventsubscription.datacollection.prometheus.DataCollectionPublisher;
import io.nwdaf.eventsubscription.kafka.KafkaProducer;
import io.nwdaf.eventsubscription.model.NfLoadLevelInformation;
import io.nwdaf.eventsubscription.model.NnwdafEventsSubscription;
import io.nwdaf.eventsubscription.model.NnwdafEventsSubscriptionNotification;
import io.nwdaf.eventsubscription.model.NwdafEvent.NwdafEventEnum;
import io.nwdaf.eventsubscription.notify.NotificationUtil;
import io.nwdaf.eventsubscription.notify.NotifyListener;
import io.nwdaf.eventsubscription.notify.NotifyPublisher;
import io.nwdaf.eventsubscription.repository.eventmetrics.entities.NfLoadLevelInformationTable;
import io.nwdaf.eventsubscription.repository.redis.RedisMetricsRepository;
import io.nwdaf.eventsubscription.repository.redis.RedisNotificationRepository;
import io.nwdaf.eventsubscription.repository.redis.RedisSubscriptionRepository;
import io.nwdaf.eventsubscription.repository.redis.entities.NfLoadLevelInformationHash;
import io.nwdaf.eventsubscription.repository.redis.entities.NnwdafEventsSubscriptionCached;
import io.nwdaf.eventsubscription.repository.redis.entities.NnwdafEventsSubscriptionNotificationCached;
import io.nwdaf.eventsubscription.service.MetricsService;
import io.nwdaf.eventsubscription.service.NotificationService;
import io.nwdaf.eventsubscription.service.SubscriptionsService;
import io.nwdaf.eventsubscription.utilities.ParserUtil;

@EnableConfigurationProperties(NwdafSubProperties.class)
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EntityScan({ "io.nwdaf.eventsubscription.repository" })
@EnableAutoConfiguration(exclude = { JacksonAutoConfiguration.class, JvmMetricsAutoConfiguration.class,
		LogbackMetricsAutoConfiguration.class, MetricsAutoConfiguration.class })
public class NwdafSubApplication {

	private static final Logger log = LoggerFactory.getLogger(NwdafSubApplication.class);

	@Autowired
	private NotifyPublisher notifyPublisher;

	@Autowired
	private DataCollectionPublisher dataCollectionPublisher;

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private DummyDataProducerPublisher dummyDataProducerPublisher;

	@Autowired
	KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	MetricsService metricsService;

	@Autowired
	SubscriptionsService subscriptionsService;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	Environment env;

	@Autowired
	KafkaProducer producer;

	@Autowired
	RedisMetricsRepository redisRepository;

	@Autowired
	RedisSubscriptionRepository redisSubscriptionRepository;

	@Autowired
	RedisNotificationRepository redisNotificationRepository;

	@Autowired
	NotificationService notificationService;

	public static void main(String[] args) {
		SpringApplication.run(NwdafSubApplication.class, args);

	}

	@Bean
	public CommandLineRunner resetDb() {
		return args -> {
			if (!subscriptionsService.truncate()) {
				log.error("Truncate subscription table failed!");
				return;
			}
		};
	}

	@Bean
	public CommandLineRunner resetMetricsDb() {
		return args -> {
			if (!metricsService.truncate()) {
				log.error("Truncate nf_load & ue_mobility tables failed!");
				return;
			}
			if (!notificationService.truncate()) {
				log.error("Truncate notification table failed!");
				return;
			}
		};
	}

	@Bean
	public CommandLineRunner run() throws JsonProcessingException {

		return args -> {
			Long subId = 0l;
			File test = new File("test.json");
			String uri = env.getProperty("nnwdaf-eventsubscription.client.prod-url");
			Integer default_port = ParserUtil
						.safeParseInteger(env.getProperty("nnwdaf-eventsubscription.client.port"));
			int no_clients=5;
			int no_subs=400;
			if(uri==null) {return;}
			for (int n = 0; n < 5; n++) {
				System.out.println("test iteration: " + n);
				if (!subscriptionsService.truncate()) {
					log.error("Truncate subscription table failed!");
					return;
				}
				for (int i = 0; i < no_subs/no_clients; i++) {
					for (int j = 0; j < no_clients; j++) {
						Integer current_port = default_port + j;
						String parsedUri = uri.replace(default_port.toString(), current_port.toString());
						if (j > 0 && !uri.contains("localhost")) {
							parsedUri = parsedUri.replace(":" + current_port.toString(),
									ParserUtil.safeParseString(j + 1) + ":" + current_port.toString());
						}
						subscriptionsService
								.create(objectMapper.reader().readValue(test, NnwdafEventsSubscription.class)
										.notificationURI(parsedUri));
					}
				}
				System.out.println("Created "+no_subs+" subs for scenario with "+no_clients+" clients.");
				// NotificationUtil.wakeUpDataProducer("kafka",
				// 		NwdafEventEnum.NF_LOAD,
				// 		null,
				// 		dataCollectionPublisher,
				// 		dummyDataProducerPublisher,
				// 		producer,
				// 		objectMapper);
				NotificationUtil.wakeUpDataProducer("kafka",
						NwdafEventEnum.UE_MOBILITY,
						null,
						dataCollectionPublisher,
						dummyDataProducerPublisher,
						producer,
						objectMapper);
				notifyPublisher.publishNotification(subId);
				Thread.sleep(100000);
				NotifyListener.stop();
				Thread.sleep(2000);
			}
		};
	}

	// @Bean
	public CommandLineRunner redisTest() {
		return args -> {
			NfLoadLevelInformation nfLoadLevelInformation = new NfLoadLevelInformation().nfInstanceId(UUID.randomUUID())
					.nfCpuUsage(100).time(Instant.now());
			NfLoadLevelInformationTable bodyTable = new NfLoadLevelInformationTable(nfLoadLevelInformation);
			System.out.println(bodyTable.getData());
			NfLoadLevelInformationHash nfLoadLevelInformationCached = new NfLoadLevelInformationHash();
			nfLoadLevelInformationCached.setData(nfLoadLevelInformation);
			System.out.println("before:" + nfLoadLevelInformationCached.toString());
			redisRepository.save(nfLoadLevelInformationCached);

			NfLoadLevelInformationHash res = redisRepository
					.findById(nfLoadLevelInformationCached.getNfInstanceId().toString()).orElse(null);
			System.out.println("after:" + res.toString());

			NnwdafEventsSubscriptionCached nnwdafEventsSubscriptionCached = new NnwdafEventsSubscriptionCached();
			File test = new File("test.json");
			NnwdafEventsSubscription sub = objectMapper.reader().readValue(test, NnwdafEventsSubscription.class);
			sub.setId(1l);
			nnwdafEventsSubscriptionCached.setSub(sub);
			System.out.println("before:" + nnwdafEventsSubscriptionCached.toString());
			redisSubscriptionRepository.save(nnwdafEventsSubscriptionCached);

			NnwdafEventsSubscriptionCached subRes = redisSubscriptionRepository
					.findById(nnwdafEventsSubscriptionCached.getId()).orElse(null);
			System.out.println("after:" + subRes.toString());

			NnwdafEventsSubscriptionNotificationCached notificationCached = new NnwdafEventsSubscriptionNotificationCached();
			File notifTest = new File("notifTest.json");
			NnwdafEventsSubscriptionNotification notification = objectMapper.reader().readValue(notifTest,
					NnwdafEventsSubscriptionNotification.class);
			notificationCached.setNotification(notification);
			System.out.println("before:" + notificationCached.toString());
			redisNotificationRepository.save(notificationCached);

			NnwdafEventsSubscriptionNotificationCached notifRes = redisNotificationRepository
					.findById(notificationCached.getId()).orElse(null);
			System.out.println("after:" + notifRes.toString());
		};
	}

	// @Bean
	public CommandLineRunner redisQuerryTest() {
		return args -> {
			NfLoadLevelInformationHash nfLoadLevelInformationCached = new NfLoadLevelInformationHash();
			nfLoadLevelInformationCached.setData(
					new NfLoadLevelInformation().nfInstanceId(UUID.randomUUID()).nfCpuUsage(100).time(Instant.now()));
			System.out.println("before:" + nfLoadLevelInformationCached.toString());
			redisRepository.save(nfLoadLevelInformationCached);

			List<NfLoadLevelInformationHash> res = redisRepository.findBy(null, null);
			System.out.println("after:" + res.toString());
		};
	}

	public static Logger getLogger() {
		return NwdafSubApplication.log;
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}
}
