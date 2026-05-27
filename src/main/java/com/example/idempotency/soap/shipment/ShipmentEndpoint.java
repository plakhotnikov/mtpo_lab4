package com.example.idempotency.soap.shipment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;

/**
 * SOAP-эндпоинт (Spring-WS, контракт-первый: см. xsd/shipments.xsd).
 *
 * Идемпотентность достигается двумя слоями:
 *   1. HTTP-уровень: IdempotencyFilter перехватывает /api/soap/* по заголовку
 *      Idempotency-Key - кэширует SOAP-envelope ответа целиком (с Content-Type
 *      text/xml). Реплей возвращает байт-в-байт тот же envelope.
 *   2. Домен-уровень: UNIQUE(tracking_number) - даже если фильтр выключен
 *      (non-idempotent профиль), второй вызов с тем же tracking_number
 *      вернёт существующую запись.
 *
 * Реализация работает с DOM напрямую, чтобы избежать кодген JAXB и не
 * раздувать pom доп. плагином - для демо лабораторной этого достаточно.
 */
@Endpoint
public class ShipmentEndpoint {

    private static final Logger log = LoggerFactory.getLogger(ShipmentEndpoint.class);
    public static final String NAMESPACE = "http://example.com/idempotency/shipments";

    private final ShipmentService service;

    public ShipmentEndpoint(ShipmentService service) {
        this.service = service;
    }

    @PayloadRoot(namespace = NAMESPACE, localPart = "createShipmentRequest")
    @ResponsePayload
    public DOMSource createShipment(@RequestPayload DOMSource request) throws Exception {
        try {
            Element root = rootElement(request);
            String trackingNumber = childText(root, "trackingNumber");
            String recipient = childText(root, "recipient");

            Shipment created = service.create(trackingNumber, recipient);

            Document responseDoc = newDocument();
            Element response = responseDoc.createElementNS(NAMESPACE, "createShipmentResponse");
            responseDoc.appendChild(response);
            appendChild(responseDoc, response, "id", created.getId().toString());
            appendChild(responseDoc, response, "trackingNumber", created.getTrackingNumber());
            appendChild(responseDoc, response, "status", created.getStatus());
            return new DOMSource(responseDoc);
        } catch (Exception e) {
            log.error("SOAP createShipment failed", e);
            throw e;
        }
    }

    @PayloadRoot(namespace = NAMESPACE, localPart = "getShipmentRequest")
    @ResponsePayload
    public DOMSource getShipment(@RequestPayload DOMSource request) throws Exception {
        Element root = rootElement(request);
        String trackingNumber = childText(root, "trackingNumber");
        Shipment shipment = service.getByTrackingNumber(trackingNumber);

        Document responseDoc = newDocument();
        Element response = responseDoc.createElementNS(NAMESPACE, "getShipmentResponse");
        responseDoc.appendChild(response);
        appendChild(responseDoc, response, "id", shipment.getId().toString());
        appendChild(responseDoc, response, "trackingNumber", shipment.getTrackingNumber());
        appendChild(responseDoc, response, "recipient", shipment.getRecipient());
        appendChild(responseDoc, response, "status", shipment.getStatus());
        return new DOMSource(responseDoc);
    }

    private static Element rootElement(DOMSource source) {
        Node node = source.getNode();
        if (node instanceof Document doc) return doc.getDocumentElement();
        if (node instanceof Element el) return el;
        throw new IllegalStateException("Unexpected DOMSource node type: " + (node != null ? node.getClass() : "null"));
    }

    private static String childText(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(n.getLocalName())) {
                return n.getTextContent();
            }
        }
        throw new IllegalArgumentException("Missing element: " + localName);
    }

    private static void appendChild(Document doc, Element parent, String localName, String text) {
        Element child = doc.createElementNS(NAMESPACE, localName);
        child.setTextContent(text);
        parent.appendChild(child);
    }

    private static Document newDocument() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().newDocument();
    }
}
