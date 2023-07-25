package io.nwdaf.eventsubscription.requestbuilders;

import org.apache.hc.client5.http.classic.HttpClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.TimeZone;

import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.bdwise.prometheus.client.converter.ConvertUtil;
import com.bdwise.prometheus.client.converter.query.DefaultQueryResult;
import com.bdwise.prometheus.client.converter.query.VectorData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.nwdaf.eventsubscription.Constants;
import io.nwdaf.eventsubscription.NwdafSubApplication;
import io.nwdaf.eventsubscription.model.EventNotification;
import io.nwdaf.eventsubscription.model.EventSubscription;
import io.nwdaf.eventsubscription.model.NfLoadLevelInformation;
import io.nwdaf.eventsubscription.model.NnwdafEventsSubscription;
import io.nwdaf.eventsubscription.model.NnwdafEventsSubscriptionNotification;
import io.nwdaf.eventsubscription.model.NotificationMethod;
import io.nwdaf.eventsubscription.model.NwdafEvent;
import io.nwdaf.eventsubscription.model.NwdafEvent.NwdafEventEnum;
import io.nwdaf.eventsubscription.repository.eventsubscription.entities.NnwdafEventsSubscriptionTable;
import io.nwdaf.eventsubscription.responsebuilders.NotificationBuilder;

public class PrometheusRequestBuilder {
	
	private RestTemplate template;
	
	private RestTemplate setupTemplate() {
		HttpComponentsClientHttpRequestFactory httpRequestFactory = new HttpComponentsClientHttpRequestFactory();
		HttpClient httpClient = HttpClientBuilder.create().build();
		httpRequestFactory.setHttpClient(httpClient);
		return new RestTemplate(httpRequestFactory);
	}
	private HttpEntity<MultiValueMap<String, String>> setupRequest(OffsetDateTime now,String query) {
		String nowString = now.toString();
		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Type", "application/x-www-form-urlencoded");
		headers.set("Accept-Encoding", "gzip, deflate, br");
		headers.set("Connection", "keep-alive");
		headers.set("Accept", "*/*");
		String encoding = Base64.getEncoder().encodeToString(("admin:admin").getBytes());
		headers.set(HttpHeaders.AUTHORIZATION, "Basic "+encoding);
		MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
		map.add("query",query);
		map.add("time",nowString);
		HttpEntity<MultiValueMap<String, String>> reqToPrometheus = new HttpEntity<>(map,headers);
		return reqToPrometheus;
	}
	
	@SuppressWarnings("unchecked")
	public <T> List<T> execute(NwdafEventEnum eType,String prometheus_url) throws JsonProcessingException {
		Logger log = NwdafSubApplication.getLogger();
		OffsetDateTime now = OffsetDateTime.now();
		//setup the connection rest template
		template = setupTemplate();
		
		NotificationBuilder notifBuilder = new NotificationBuilder();
		switch(eType) {
		case NF_LOAD:
//			System.out.println("query: "+queryString);
			HttpEntity<MultiValueMap<String, String>> reqToPrometheus = setupRequest(now,Constants.cpuQuerry);
//			long start = System.nanoTime();
			String rtVal;
			try {
			rtVal = template.postForObject(prometheus_url, reqToPrometheus, String.class);
			}catch(RestClientException e) {
				return null;
			}
//			long diff = (System.nanoTime()-start) / 1000000l;
//        	System.out.println("prometheus actual post req delay: "+diff+"ms");
			DefaultQueryResult<VectorData> result = ConvertUtil.convertQueryResultString(rtVal);
	
			String resTime=null;
			Double value=null;
			List<List<Integer>> data = new ArrayList<>();
			List<List<String>> dataOptionals = new ArrayList<>();
			List<Double> maxCpus = new ArrayList<>();
			List<Double> maxMems = new ArrayList<>();
			List<Double> maxStorages = new ArrayList<>();
			Integer c = 0;
			for(int i=0;i<result.getResult().size();i++) {
				data.add(new ArrayList<>(Arrays.asList(null,null,null,null,null,null)));
				dataOptionals.add(new ArrayList<>(Arrays.asList(null,null,null,null,null,null,null,null,null,null)));
			}
			for(VectorData vectorData : result.getResult()) {
				resTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(Math.round(vectorData.getDataValue().getTimestamp()*1000)), TimeZone.getDefault().toZoneId()).toString();
				if(dataOptionals.get(c).get(9)==null) {
					dataOptionals.get(c).set(9,resTime);
				}
				if(dataOptionals.get(c).get(0)==null) {
					dataOptionals.get(c).set(0,vectorData.getMetric().get("nfType"));
				}
				if(dataOptionals.get(c).get(1)==null) {
					// add hyphens '-' to docker/id/[first 32 digits] -> nf instance id (UUID)
					dataOptionals.get(c).set(1,vectorData.getMetric().get("id").substring(8,39).replaceFirst( "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5" ));
				}
				value = vectorData.getDataValue().getValue();
				log.info(String.format("%s", vectorData.getMetric().get("name")));
				log.info(String.format("%s %10.2f ",
						OffsetDateTime.ofInstant(Instant.ofEpochMilli(Math.round(vectorData.getDataValue().getTimestamp()*1000)), TimeZone.getDefault().toZoneId()),
						vectorData.getDataValue().getValue()
						));
				data.get(c).set(0,(int) Math.round(value));	
				c++;
			}
			reqToPrometheus = setupRequest(now,Constants.memQuerry);
			try {
				rtVal = template.postForObject(prometheus_url, reqToPrometheus, String.class);
				}catch(RestClientException e) {
					return null;
				}
			result = ConvertUtil.convertQueryResultString(rtVal);
			c=0;
			for(VectorData vectorData : result.getResult()) {
				value = vectorData.getDataValue().getValue();
				data.get(c).set(1,(int) Math.round(value));	
				c++;
			}
			reqToPrometheus = setupRequest(now,Constants.storageQuerry);
			try {
				rtVal = template.postForObject(prometheus_url, reqToPrometheus, String.class);
				}catch(RestClientException e) {
					return null;
				}
			result = ConvertUtil.convertQueryResultString(rtVal);
			c=0;
			for(VectorData vectorData : result.getResult()) {
				value = vectorData.getDataValue().getValue();
				data.get(c).set(2,(int) Math.round(value));	
				int loadAverage = (data.get(c).get(0) + data.get(c).get(1) + data.get(c).get(2)) / 3;
				data.get(c).set(3, loadAverage);
				c++;
			}
			String[] maxQuerries = {Constants.maxCpuQuerry,Constants.maxMemQuerry,Constants.maxStorageQuerry};
			for(int i=0;i<3;i++) {
				reqToPrometheus = setupRequest(now, maxQuerries[i]);
				try {
					rtVal = template.postForObject(prometheus_url, reqToPrometheus, String.class);
					}catch(RestClientException e) {
						return null;
					}
				result = ConvertUtil.convertQueryResultString(rtVal);
				for(VectorData vectorData : result.getResult()) {
					value = vectorData.getDataValue().getValue();
					if(i==0) {
						maxCpus.add(value);
					}
					else if(i==1) {
						maxMems.add(value);
					}
					else {
						maxStorages.add(value);
					}
				}
			}
			for(int i=0;i<maxCpus.size();i++) {
				data.get(i).set(4,(int) Math.round((maxCpus.get(i)+maxMems.get(i)+maxStorages.get(i))/3));
			}
			return (List<T>) notifBuilder.makeNfLoadLevelInformation(data, dataOptionals);
		default:
			break;
	}
		
		return null;
	}
}
