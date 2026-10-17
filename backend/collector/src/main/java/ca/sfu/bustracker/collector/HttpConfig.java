package ca.sfu.bustracker.collector;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

@Configuration
public class HttpConfig {
    @Bean
    HttpClient httpClient(CollectorProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(props.getHttpTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
