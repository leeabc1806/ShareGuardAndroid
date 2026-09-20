package com.shareguard.backend.auth;

import com.shareguard.backend.common.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

@Component
public class PortOneIdentityClient implements PortOneIdentityVerifier {
    private final RestClient restClient;
    private final PortOneProperties properties;

    public PortOneIdentityClient(RestClient.Builder builder, PortOneProperties properties) {
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
        this.properties = properties;
    }

    @Override
    public VerifiedIdentity verify(String identityVerificationId) {
        if (properties.apiSecret().isBlank()) {
            throw new ApiException(503, "PORTONE_API_SECRET이 설정되지 않았습니다.");
        }

        final JsonNode response;
        try {
            response = restClient.get()
                .uri("/identity-verifications/{id}", identityVerificationId)
                .header(HttpHeaders.AUTHORIZATION, "PortOne " + properties.apiSecret())
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new ApiException(400, "포트원 본인인증 건을 찾을 수 없습니다.");
            }
            throw new ApiException(502, "포트원 본인인증 조회에 실패했습니다.");
        } catch (RestClientException exception) {
            throw new ApiException(502, "포트원 본인인증 서버에 연결할 수 없습니다.");
        }

        if (response == null) {
            throw new ApiException(502, "포트원에서 빈 인증 결과를 반환했습니다.");
        }
        // The current V2 GET response is the identity object. The wrapper fallback keeps the
        // parser tolerant of SDK/proxy response shapes without weakening verification.
        var verification = response.hasNonNull("identityVerification")
            ? response.get("identityVerification") : response;
        var returnedId = text(verification, "id");
        if (!returnedId.isBlank() && !returnedId.equals(identityVerificationId)) {
            throw new ApiException(400, "요청한 본인인증 ID와 포트원 응답이 일치하지 않습니다.");
        }
        if (!"VERIFIED".equals(text(verification, "status"))) {
            throw new ApiException(400, "완료되지 않은 본인인증입니다.");
        }

        var customer = verification.get("verifiedCustomer");
        if (customer == null || customer.isNull()) {
            throw new ApiException(502, "포트원 인증 고객 정보가 없습니다.");
        }
        var phoneNumber = text(customer, "phoneNumber").replaceAll("[^0-9]", "");
        if (phoneNumber.length() < 10) {
            throw new ApiException(422, "검증된 휴대전화 번호를 확인할 수 없습니다.");
        }

        return new VerifiedIdentity(
            identityVerificationId,
            text(customer, "name"),
            phoneNumber,
            text(customer, "birthDate"),
            text(customer, "ci"),
            text(customer, "di")
        );
    }

    private String text(JsonNode node, String field) {
        var value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }
}
