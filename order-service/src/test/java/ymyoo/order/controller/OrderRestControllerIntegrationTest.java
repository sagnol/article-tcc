package ymyoo.order.controller;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.rule.OutputCapture;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class OrderRestControllerIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Rule
    public OutputCapture outputCapture = new OutputCapture();

    @Test
    public void placeOrder() {
        // given
        final String requestURL = "/api/v1/orders";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("productId", "prd-0001");
        requestBody.put("qty", 10);
        requestBody.put("paymentAmt", 10000);

        // when
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(requestURL, new HttpEntity(requestBody, headers), String.class);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // If something fails, a polite workflow would explicitly cancel the successful reservations. - http://pautasso.info/talks/2014/wsrest/tcc/rest-tcc.html#/tcc-http-protocol-fail-cancel
    @Test
    public void placeOrder_Failure_Before_Confirm_By_Cancel() {
        // given
        final String requestURL = "/api/v1/orders";

        // 결제 제한 금액 초과로 인해 재고 차감 try에는 성공하지만 결제 try 시 오류나는 경우를 만듬
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("productId", "prd-0001");
        requestBody.put("qty", 1);
        requestBody.put("paymentAmt", 300000);

        // when
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(requestURL, new HttpEntity(requestBody, headers), String.class);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        List<String> uris = extractParticipantLinkURIs(outputCapture.toString());

        // Resources가 Cancel 되었는지 확인
        uris.forEach(uri -> {
            ResponseEntity<String> confirmResponse = restTemplate.exchange(uri, HttpMethod.PUT, null, String.class);
            assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        });
    }

    // If something fails, do nothing. The reserved resources will eventually timeout. - http://pautasso.info/talks/2014/wsrest/tcc/rest-tcc.html#/tcc-http-protocol-fail-cancel
    @Test
    public void placeOrder_Failure_Before_Confirm_By_Timeout() throws InterruptedException {
        // given
        final String requestURL = "/api/v1/orders";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("productId", "prd-0002");
        requestBody.put("qty", 1);
        requestBody.put("paymentAmt", 20000);

        // when
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(requestURL, new HttpEntity(requestBody, headers), String.class);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        List<String> uris = extractParticipantLinkURIs(outputCapture.toString());

        // 타임 아웃 테스트를 위한 대기
        waitCurrentThread(5);

        // 타임 아웃 확인(TCC Timeout)
        uris.forEach(uri -> {
            ResponseEntity<String> confirmResponse = restTemplate.exchange(uri, HttpMethod.PUT, null, String.class);
            assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        });
    }

    private List<String> extractParticipantLinkURIs(String text) {
        String urlPrefix = "ParticipantLink URI :";
        List<String> containedUrls = new ArrayList<>();
        String urlRegex = urlPrefix + "((https?|ftp|gopher|telnet|file):((//)|(\\\\))+[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]*)";
        Pattern pattern = Pattern.compile(urlRegex, Pattern.CASE_INSENSITIVE);
        Matcher urlMatcher = pattern.matcher(text);

        while (urlMatcher.find()) {
            String url = text.substring(urlMatcher.start(0), urlMatcher.end(0));
            url = url.substring(urlPrefix.length(), url.length());

            containedUrls.add(url);
        }

        return containedUrls;
    }

    private void waitCurrentThread(int seconds) throws InterruptedException {
        Thread.currentThread().sleep(TimeUnit.SECONDS.toMillis(seconds));
    }
}