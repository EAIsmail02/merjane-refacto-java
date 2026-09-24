package com.nimbleways.springboilerplate.controllers;

import com.nimbleways.springboilerplate.entities.Order;
import com.nimbleways.springboilerplate.entities.Product;
import com.nimbleways.springboilerplate.entities.ProductType;
import com.nimbleways.springboilerplate.repositories.OrderRepository;
import com.nimbleways.springboilerplate.repositories.ProductRepository;
import com.nimbleways.springboilerplate.services.implementations.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

// Specify the controller class you want to test
// This indicates to spring boot to only load UsersController into the context
// Which allows a better performance and needs to do less mocks
@SpringBootTest
@AutoConfigureMockMvc
class MyControllerIntegrationTests {
        @Autowired
        private MockMvc mockMvc;

        @MockBean
        private NotificationService notificationService;

        @Autowired
        private OrderRepository orderRepository;

        @Autowired
        private ProductRepository productRepository;

        @Test
        void processOrderShouldReturn() throws Exception {
                List<Product> allProducts = createProducts();
                Set<Product> orderItems = new HashSet<Product>(allProducts);
                Order order = createOrder(orderItems);
                productRepository.saveAll(allProducts);
                order = orderRepository.save(order);
                mockMvc.perform(post("/orders/{orderId}/processOrder", order.getId())
                                .contentType("application/json"))
                                .andExpect(status().isOk());
                Order resultOrder = orderRepository.findById(order.getId()).get();
                assertEquals(order.getId(), resultOrder.getId());

                // Per-product business assertions: each product type's rule was actually applied,
                // not just "the HTTP call returned 200".
                assertAvailable("USB Cable", 29); // NORMAL, in stock -> decremented
                assertAvailable("USB Dongle", 0); // NORMAL, out of stock with lead time -> unchanged, delay sent
                assertAvailable("Butter", 29); // EXPIRABLE, not expired, in stock -> decremented
                assertAvailable("Milk", 0); // EXPIRABLE, already expired -> zeroed
                assertAvailable("Watermelon", 29); // SEASONAL, in season & in stock -> decremented
                assertAvailable("Grapes", 30); // SEASONAL, season not started yet -> unchanged

                verify(notificationService, times(1)).sendDelayNotification(10, "USB Dongle");
                verify(notificationService, times(1)).sendExpirationNotification(eq("Milk"), any());
                verify(notificationService, times(1)).sendOutOfStockNotification("Grapes");
        }

        // --- Characterization tests: lock in current behavior before refactor ---

        @Test
        void processOrderThrowsWhenOrderDoesNotExist() {
                // A missing order surfaces as an uncaught NoSuchElementException (from
                // Optional#orElseThrow()) which MockMvc re-throws wrapped, since nothing maps it to a
                // response. In a real deployment this reaches the client as HTTP 500.
                Exception exception = assertThrows(Exception.class, () -> mockMvc
                                .perform(post("/orders/{orderId}/processOrder", 999_999L)
                                                .contentType("application/json")));
                assertInstanceOf(NoSuchElementException.class, exception.getCause());
        }

        @Test
        void processOrderNormalProductOutOfStockWithNoLeadTimeDoesNothing() throws Exception {
                Product product = new Product(null, 0, 0, ProductType.NORMAL, "Unobtainium Cable", null, null,
                                null);
                productRepository.save(product);
                Order order = createOrder(new HashSet<>(List.of(product)));
                order = orderRepository.save(order);

                mockMvc.perform(post("/orders/{orderId}/processOrder", order.getId())
                                .contentType("application/json"))
                                .andExpect(status().isOk());

                assertAvailable("Unobtainium Cable", 0);
                verifyNoInteractions(notificationService);
        }

        @Test
        void processOrderSeasonalProductWhoseLeadTimeExceedsSeasonEndIsMarkedOutOfStock() throws Exception {
                // available=0 so the happy path (in season AND in stock) doesn't shadow the
                // "lead time pushes past season end" branch this test targets.
                Product product = new Product(null, 30, 0, ProductType.SEASONAL, "Pumpkin", null,
                                LocalDate.now().minusDays(10), LocalDate.now().plusDays(3));
                productRepository.save(product);
                Order order = createOrder(new HashSet<>(List.of(product)));
                order = orderRepository.save(order);

                mockMvc.perform(post("/orders/{orderId}/processOrder", order.getId())
                                .contentType("application/json"))
                                .andExpect(status().isOk());

                assertAvailable("Pumpkin", 0);
                verify(notificationService, times(1)).sendOutOfStockNotification("Pumpkin");
        }

        @Test
        void processOrderExpirableProductAlreadyExpiredSendsExpirationNotification() throws Exception {
                Product product = new Product(null, 5, 12, ProductType.EXPIRABLE, "Yogurt",
                                LocalDate.now().minusDays(1), null, null);
                productRepository.save(product);
                Order order = createOrder(new HashSet<>(List.of(product)));
                order = orderRepository.save(order);

                mockMvc.perform(post("/orders/{orderId}/processOrder", order.getId())
                                .contentType("application/json"))
                                .andExpect(status().isOk());

                assertAvailable("Yogurt", 0);
                verify(notificationService, times(1)).sendExpirationNotification("Yogurt", product.getExpiryDate());
        }

        private void assertAvailable(String productName, int expectedAvailable) {
                Product product = productRepository.findFirstByName(productName).orElseThrow();
                assertEquals(expectedAvailable, product.getAvailable());
        }

        private static Order createOrder(Set<Product> products) {
                Order order = new Order();
                order.setItems(products);
                return order;
        }

        private static List<Product> createProducts() {
                List<Product> products = new ArrayList<>();
                products.add(new Product(null, 15, 30, ProductType.NORMAL, "USB Cable", null, null, null));
                products.add(new Product(null, 10, 0, ProductType.NORMAL, "USB Dongle", null, null, null));
                products.add(new Product(null, 15, 30, ProductType.EXPIRABLE, "Butter", LocalDate.now().plusDays(26),
                                null, null));
                products.add(new Product(null, 90, 6, ProductType.EXPIRABLE, "Milk", LocalDate.now().minusDays(2),
                                null, null));
                products.add(new Product(null, 15, 30, ProductType.SEASONAL, "Watermelon", null,
                                LocalDate.now().minusDays(2), LocalDate.now().plusDays(58)));
                products.add(new Product(null, 15, 30, ProductType.SEASONAL, "Grapes", null,
                                LocalDate.now().plusDays(180), LocalDate.now().plusDays(240)));
                return products;
        }
}
