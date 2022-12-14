package com.soa.labs.orderservice.service;

import com.soa.labs.orderservice.dto.InventoryResponse;
import com.soa.labs.orderservice.dto.OrderDto;
import com.soa.labs.orderservice.dto.OrderLineItemsDto;
import com.soa.labs.orderservice.dto.OrderRequest;
import com.soa.labs.orderservice.event.OrderPlacedEvent;
import com.soa.labs.orderservice.model.Order;
import com.soa.labs.orderservice.model.OrderLineItems;
import com.soa.labs.orderservice.repository.OrderRepository;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final WebClient.Builder webClientBuilder;
    private final Tracer tracer;
    private final KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate;

    public OrderService(OrderRepository orderRepository, WebClient.Builder webClientBuilder,
                        Tracer tracer, KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate) {
        this.orderRepository = orderRepository;
        this.webClientBuilder = webClientBuilder;
        this.tracer = tracer;
        this.kafkaTemplate = kafkaTemplate;
    }

    public String placeOrder(OrderRequest orderRequest) {
        Order order = new Order();
        order.setOrderNumber(UUID.randomUUID().toString());

        var orderLineItems = orderRequest.getOrderLineItemsDtoList()
                .stream()
                .map(this::mapToDto).toList();

        order.setOrderLineItemsList(orderLineItems);

        var skuCodes = order.getOrderLineItemsList().stream()
                .map(OrderLineItems::getSkuCode)
                .toList();


        Span inventoryServiceSpan = tracer.nextSpan().name("InventoryServiceSpan");

        try (Tracer.SpanInScope spanInScope = tracer.withSpan(inventoryServiceSpan)) {
            // Call inventory service and place order if product is in
            var inventoryResponses = webClientBuilder.build().get()
                    .uri("http://inventory-service/api/inventory/isinstock/",
                            uriBuilder -> uriBuilder.queryParam("skuCode", skuCodes).build())
                    .retrieve()
                    .bodyToMono(InventoryResponse[].class)
                    .block();

            assert inventoryResponses != null;
            var result = Arrays.stream(inventoryResponses)
                    .allMatch(InventoryResponse::getIsInStock);

            if (Boolean.TRUE.equals(result)) {
                orderRepository.save(order);
                kafkaTemplate.send("notificationTopic", new OrderPlacedEvent(order.getOrderNumber()));
                return "Order places successfully";
            } else {
                throw new IllegalArgumentException("product is not in the stock");
            }
        } finally {
            inventoryServiceSpan.end();
        }
    }

    public List<OrderDto> getOrders() {
        return orderRepository.findAll().stream()
                .map(this::mapToOrderDto).toList();
    }

    private OrderLineItems mapToDto(OrderLineItemsDto orderLineItemsDto) {
        OrderLineItems orderLineItems = new OrderLineItems();
        orderLineItems.setPrice(orderLineItemsDto.getPrice());
        orderLineItems.setQuantity(orderLineItemsDto.getQuantity());
        orderLineItems.setSkuCode(orderLineItemsDto.getSkuCode());

        return orderLineItems;
    }

    private OrderDto mapToOrderDto(Order order) {

        return OrderDto.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .build();
    }
}
