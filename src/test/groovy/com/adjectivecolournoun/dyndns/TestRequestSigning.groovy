package com.adjectivecolournoun.dyndns

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables
import software.amazon.awssdk.services.route53.Route53Client
import software.amazon.awssdk.services.route53.model.ChangeResourceRecordSetsResponse
import spock.lang.Specification

import java.util.function.Consumer

import static org.apache.commons.lang3.RandomStringUtils.random

class TestRequestSigning extends Specification {

    @Rule
    public final EnvironmentVariables environmentVariables = new EnvironmentVariables()

    def handler = new DynDnsHandler(Mock(Route53Client) {
        changeResourceRecordSets(_ as Consumer) >> ChangeResourceRecordSetsResponse.builder()
                .changeInfo({ cb -> cb.status('UPDATING')})
                .build()
    })

    void 'rejects requests without signatures'() {
        given:
        def event = new APIGatewayProxyRequestEvent()

        when:
        def result = handler.handleRequest(event, null)

        then:
        result.statusCode == 400
    }

    void 'validates request signature'() {
        given:
        def hostname = random(10)
        def sharedSecret = random(10)
        environmentVariables.HOSTNAME = hostname
        environmentVariables.SHARED_SECRET = sharedSecret

        def signature = "$hostname:$sharedSecret".sha256()

        def event = new APIGatewayProxyRequestEvent()
                .withHeaders('X-Forwarded-For': '')
                .withQueryStringParameters(signature: signature)

        when:
        def result = handler.handleRequest(event, null)

        then:
        result.statusCode == 204
    }

    void 'rejects signatures with bad secrets'() {
        given:
        def hostname = random(10)
        def sharedSecret = random(10)
        environmentVariables.HOSTNAME = hostname
        environmentVariables.SHARED_SECRET = sharedSecret

        def signature = "$hostname:${random(10)}".sha256()

        def event = new APIGatewayProxyRequestEvent().withQueryStringParameters(signature: signature)

        when:
        def result = handler.handleRequest(event, null)

        then:
        result.statusCode == 403
    }

    void 'rejects signatures with bad hostnames'() {
        given:
        def hostname = random(10)
        def sharedSecret = random(10)
        environmentVariables.HOSTNAME = hostname
        environmentVariables.SHARED_SECRET = sharedSecret

        def signature = "${random(10)}:$sharedSecret".sha256()

        def event = new APIGatewayProxyRequestEvent().withQueryStringParameters(signature: signature)

        when:
        def result = handler.handleRequest(event, null)

        then:
        result.statusCode == 403
    }
}