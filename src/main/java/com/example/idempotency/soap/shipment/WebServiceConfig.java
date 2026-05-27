package com.example.idempotency.soap.shipment;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ws.config.annotation.EnableWs;
import org.springframework.ws.config.annotation.WsConfigurerAdapter;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import org.springframework.xml.xsd.XsdSchema;

/**
 * Spring-WS конфигурация: MessageDispatcherServlet под /api/soap/*.
 * Существующий IdempotencyFilter (тот же бин) перехватывает /api/soap/*
 * и кэширует SOAP-envelopes - никакой SOAP-специфичной логики идемпотентности
 * не требуется.
 */
@EnableWs
@Configuration
public class WebServiceConfig extends WsConfigurerAdapter {

    @Bean
    public ServletRegistrationBean<MessageDispatcherServlet> messageDispatcherServlet(ApplicationContext applicationContext) {
        MessageDispatcherServlet servlet = new MessageDispatcherServlet();
        servlet.setApplicationContext(applicationContext);
        servlet.setTransformWsdlLocations(true);
        return new ServletRegistrationBean<>(servlet, "/api/soap/*");
    }

    @Bean(name = "shipments")
    public DefaultWsdl11Definition shipmentsWsdl(XsdSchema shipmentsSchema) {
        DefaultWsdl11Definition wsdl = new DefaultWsdl11Definition();
        wsdl.setPortTypeName("ShipmentsPort");
        wsdl.setLocationUri("/api/soap");
        wsdl.setTargetNamespace(ShipmentEndpoint.NAMESPACE);
        wsdl.setSchema(shipmentsSchema);
        return wsdl;
    }

    @Bean
    public XsdSchema shipmentsSchema() {
        return new SimpleXsdSchema(new ClassPathResource("xsd/shipments.xsd"));
    }
}
