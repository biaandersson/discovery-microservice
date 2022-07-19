package com.comeon.orderservice.service;

import com.comeon.orderservice.dto.InventoryResponse;
import com.comeon.orderservice.dto.OrderLineItemsDto;
import com.comeon.orderservice.dto.OrderRequest;
import com.comeon.orderservice.model.Order;
import com.comeon.orderservice.model.OrderLineItems;
import com.comeon.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static java.awt.SystemColor.info;

@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final WebClient.Builder webClientBuilder;

    private final Tracer tracer;

    public String placeOrder(OrderRequest orderRequest) {
        Order order = new Order();

        order.setOrderNumber(UUID.randomUUID().toString());

        List<OrderLineItems> orderLineItems =
                orderRequest.getOrderLineItemsListDto()
                        .stream()
                        .map(this::mapToDto)
                        .toList();

        order.setOrderLineItemsList(orderLineItems);

        List<String> skuCodes = order.getOrderLineItemsList().stream()
                .map(OrderLineItems::getSkuCode).toList();


        Span inventoryServiceLookup = tracer.nextSpan().name("inventoryServiceLookup").start();

        try (Tracer.SpanInScope inScope = tracer.withSpan(inventoryServiceLookup.start())) {

            /* Call Inventory Service, and place order if product is in stock */
            InventoryResponse[] inventoryResponsesArray = webClientBuilder.build()
                    .get()
                    .uri("http://inventory-service/api/inventory",
                            uriBuilder -> uriBuilder
                                    .queryParam("skuCode", skuCodes)
                                    .build())

                    .retrieve()
                    .bodyToMono(InventoryResponse[].class)
                    .block();

            boolean allProductsInStock = Arrays
                    .stream(Objects.requireNonNull(inventoryResponsesArray))
                    .allMatch(InventoryResponse::isInStock);

            if (allProductsInStock) orderRepository.save(order);
            else throw new RuntimeException("Not all products are in stock");

            return "Order placed successfully";

        } finally {
            inventoryServiceLookup.end();
        }

    }

    private OrderLineItems mapToDto(OrderLineItemsDto orderLineItemsDto) {
        OrderLineItems orderLineItems = new OrderLineItems();

        orderLineItems.setSkuCode(orderLineItemsDto.getSkuCode());
        orderLineItems.setPrice(orderLineItemsDto.getPrice());
        orderLineItems.setQuantity(orderLineItemsDto.getQuantity());

        return orderLineItems;
    }
}
