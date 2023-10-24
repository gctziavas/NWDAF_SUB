package io.nwdaf.eventsubscription;




import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.nwdaf.eventsubscription.api.config.NwdafSubProperties;
import io.nwdaf.eventsubscription.kafka.KafkaProducer;
import io.nwdaf.eventsubscription.kafka.datacollection.dummy.KafkaDummyDataPublisher;
import io.nwdaf.eventsubscription.model.NfLoadLevelInformation;
import io.nwdaf.eventsubscription.model.NnwdafEventsSubscription;
import io.nwdaf.eventsubscription.notify.NotifyListener;
import io.nwdaf.eventsubscription.notify.NotifyPublisher;
import io.nwdaf.eventsubscription.repository.redis.RedisRepository;
import io.nwdaf.eventsubscription.repository.redis.entities.NfLoadLevelInformationCached;
import io.nwdaf.eventsubscription.service.SubscriptionsService;
import io.nwdaf.eventsubscription.utilities.ParserUtil;

@EnableConfigurationProperties(NwdafSubProperties.class)
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EntityScan({"io.nwdaf.eventsubscription.repository"})
public class NwdafSubApplication {
	
	private static final Logger log = LoggerFactory.getLogger(NwdafSubApplication.class);
	
	@Autowired
	private NotifyPublisher notifyPublisher;
	
	// @Autowired
	// private DataCollectionPublisher dataCollectionPublisher;

	@Autowired
    private ApplicationContext applicationContext;

	// @Autowired
	// private DummyDataProducerPublisher dummyDataProducerPublisher;

	@Autowired
	private KafkaDummyDataPublisher kafkaDummyDataPublisher;

	@Autowired
    KafkaTemplate<String,String> kafkaTemplate;
	
//	@Autowired
//	MetricsService metricsService;

	@Autowired
	SubscriptionsService subscriptionsService;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	Environment env;

	@Autowired
	KafkaProducer producer;

	@Autowired
	RedisRepository redisRepository;
	
	public static void main(String[] args) {
		SpringApplication.run(NwdafSubApplication.class, args);

	}
	@Bean
	public CommandLineRunner resetDb(){
		return args -> {
			if(!subscriptionsService.truncate()){
					log.error("Truncate subscription table failed!");
					return;
			}
		};
	}
	// @Bean
	public CommandLineRunner run() throws JsonProcessingException{
		
		return args -> {
			if(!subscriptionsService.truncate()){
				log.error("Truncate subscription table failed!");
				return;
			}
			Long subId = 0l;
			File test = new File("test.json");
			String uri = env.getProperty("nnwdaf-eventsubscription.client.prod-url");
			if(uri != null){
				Integer default_port = ParserUtil.safeParseInteger(env.getProperty("nnwdaf-eventsubscription.client.port"));
				for(int i=0;i<40;i++){
					for(int j=0;j<5;j++){
						Integer current_port = default_port+j;
						String parsedUri = uri.replace(default_port.toString(), current_port.toString());
						if(j>0 && !uri.contains("localhost")){
							parsedUri = parsedUri.replace(":"+current_port.toString(),ParserUtil.safeParseString(j+1)+":"+current_port.toString());
						}
						subscriptionsService.create(objectMapper.reader().readValue(test,NnwdafEventsSubscription.class)
							.notificationURI(parsedUri));
					}
				}
				// dataCollectionPublisher.publishDataCollection("");
				// dummyDataProducerPublisher.publishDataCollection("dummy data production");
				kafkaDummyDataPublisher.publishDataCollection("dummy data production through kafka");
				notifyPublisher.publishNotification(subId);
				Thread.sleep(100000);
				NotifyListener.stop();
			}
		};
	}

	// @Bean
	public ApplicationRunner runner() throws IOException, JsonProcessingException{
		File test = new File("test.json");
		NnwdafEventsSubscription subscription = objectMapper.reader().readValue(test,NnwdafEventsSubscription.class);
		final String payload = objectMapper.writeValueAsString(subscription);
		return args -> {
			producer.sendMessage(payload,"topic1");
		};
	}
	
	// @Bean
	public CommandLineRunner redisTest(){
		return args -> {
			NfLoadLevelInformationCached nfLoadLevelInformationCached = new NfLoadLevelInformationCached();
			nfLoadLevelInformationCached.setData(new NfLoadLevelInformation().nfInstanceId(UUID.randomUUID()).nfCpuUsage(100).time(Instant.now()));
			System.out.println("before:"+nfLoadLevelInformationCached.toString());
			redisRepository.save(nfLoadLevelInformationCached);
			
			List<NfLoadLevelInformationCached> res = 
				redisRepository.findAll()
					.stream()
					.filter(e -> e.getIdObject()!=null && e.getIdObject().getNfInstanceId()
						.equals(nfLoadLevelInformationCached.getData().getNfInstanceId()))
						.collect(Collectors.toList());
			System.out.println("after:"+res.toString());
		};
	}

	public static Logger getLogger() {
		return NwdafSubApplication.log;
	}

    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }
	
}
