package com.nimbleways.springboilerplate.services.implementations;

import com.nimbleways.springboilerplate.entities.Product;
import com.nimbleways.springboilerplate.entities.ProductType;
import com.nimbleways.springboilerplate.repositories.ProductRepository;
import com.nimbleways.springboilerplate.utils.Annotations.UnitTest;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@UnitTest
class MyUnitTests {

    @Mock
    private NotificationService notificationService;
    @Mock
    private ProductRepository productRepository;
    @InjectMocks
    private ProductService productService;

    @Test
    void test() {
        // GIVEN
        Product product = new Product(null, 15, 0, ProductType.NORMAL, "RJ45 Cable", null, null, null);

        when(productRepository.save(product)).thenReturn(product);

        // WHEN
        productService.notifyDelay(product.getLeadTime(), product);

        // THEN
        assertEquals(0, product.getAvailable());
        assertEquals(15, product.getLeadTime());
        verify(productRepository, times(1)).save(product);
        verify(notificationService, times(1)).sendDelayNotification(product.getLeadTime(), product.getName());
    }

    // --- NORMAL ---

    @Test
    void handleNormalProduct_inStock_decrementsAndSaves() {
        Product product = new Product(null, 5, 3, ProductType.NORMAL, "USB Cable", null, null, null);
        when(productRepository.save(product)).thenReturn(product);

        productService.handleNormalProduct(product);

        assertEquals(2, product.getAvailable());
        verify(productRepository, times(1)).save(product);
        verifyNoInteractions(notificationService);
    }

    @Test
    void handleNormalProduct_outOfStockWithLeadTime_sendsDelayNotification() {
        Product product = new Product(null, 7, 0, ProductType.NORMAL, "USB Dongle", null, null, null);
        when(productRepository.save(product)).thenReturn(product);

        productService.handleNormalProduct(product);

        assertEquals(0, product.getAvailable());
        assertEquals(7, product.getLeadTime());
        verify(productRepository, times(1)).save(product);
        verify(notificationService, times(1)).sendDelayNotification(7, product.getName());
    }

    @Test
    void handleNormalProduct_outOfStockWithNoLeadTime_doesNothing() {
        Product product = new Product(null, 0, 0, ProductType.NORMAL, "Unobtainium Cable", null, null, null);

        productService.handleNormalProduct(product);

        assertEquals(0, product.getAvailable());
        verify(productRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    // --- SEASONAL ---

    @Test
    void handleSeasonalProduct_inSeasonAndInStock_decrementsAndSaves() {
        Product product = new Product(null, 15, 30, ProductType.SEASONAL, "Watermelon", null,
                LocalDate.now().minusDays(2), LocalDate.now().plusDays(58));
        when(productRepository.save(product)).thenReturn(product);

        productService.handleSeasonalProduct(product);

        assertEquals(29, product.getAvailable());
        verify(productRepository, times(1)).save(product);
        verifyNoInteractions(notificationService);
    }

    @Test
    void handleSeasonalProduct_leadTimeExceedsSeasonEnd_isOutOfStockAndZeroed() {
        // available=0 so the happy-path branch (in season AND in stock) does not shadow the
        // "lead time pushes past season end" branch this test targets.
        Product product = new Product(null, 90, 0, ProductType.SEASONAL, "Grapes", null,
                LocalDate.now().minusDays(2), LocalDate.now().plusDays(10));
        when(productRepository.save(product)).thenReturn(product);

        productService.handleSeasonalProduct(product);

        assertEquals(0, product.getAvailable());
        verify(notificationService, times(1)).sendOutOfStockNotification(product.getName());
        verify(productRepository, times(1)).save(product);
    }

    @Test
    void handleSeasonalProduct_beforeSeasonStart_isOutOfStockButAvailableUnchanged() {
        Product product = new Product(null, 15, 30, ProductType.SEASONAL, "Strawberries", null,
                LocalDate.now().plusDays(180), LocalDate.now().plusDays(240));
        when(productRepository.save(product)).thenReturn(product);

        productService.handleSeasonalProduct(product);

        // available is left as-is: the season simply hasn't started, stock isn't zeroed
        assertEquals(30, product.getAvailable());
        verify(notificationService, times(1)).sendOutOfStockNotification(product.getName());
        verify(productRepository, times(1)).save(product);
    }

    @Test
    void handleSeasonalProduct_seasonStartsToday_fallsThroughToDelayNotification() {
        // GIVEN: seasonStartDate is exactly today (strict isAfter(now) on start => false => season is
        // considered started, not "not yet started")
        Product product = new Product(null, 5, 0, ProductType.SEASONAL, "Watermelon", null, LocalDate.now(),
                LocalDate.now().plusDays(30));
        when(productRepository.save(product)).thenReturn(product);

        productService.handleSeasonalProduct(product);

        assertEquals(5, product.getLeadTime());
        verify(notificationService, times(1)).sendDelayNotification(5, product.getName());
        verify(notificationService, never()).sendOutOfStockNotification(any());
    }

    @Test
    void handleSeasonalProduct_seasonEndsToday_fallsThroughToDelayNotification() {
        // GIVEN: seasonEndDate is exactly today, leadTime 0 (now+0 isAfter end => false => not
        // considered "lead time exceeds season")
        Product product = new Product(null, 0, 0, ProductType.SEASONAL, "Grapes", null,
                LocalDate.now().minusDays(60), LocalDate.now());
        when(productRepository.save(product)).thenReturn(product);

        productService.handleSeasonalProduct(product);

        verify(notificationService, times(1)).sendDelayNotification(0, product.getName());
        verify(notificationService, never()).sendOutOfStockNotification(any());
    }

    @Test
    void handleSeasonalProduct_leadTimeExactlyReachesSeasonEnd_fallsThroughToDelayNotification() {
        // GIVEN: today + leadTime lands exactly on seasonEndDate (strict isAfter => false => not
        // considered "exceeds season"). available=0 so this matches how the controller actually
        // reaches this method today (out of stock, in season).
        Product product = new Product(null, 10, 0, ProductType.SEASONAL, "Strawberries", null,
                LocalDate.now().minusDays(30), LocalDate.now().plusDays(10));
        when(productRepository.save(product)).thenReturn(product);

        productService.handleSeasonalProduct(product);

        verify(notificationService, times(1)).sendDelayNotification(10, product.getName());
        verify(notificationService, never()).sendOutOfStockNotification(any());
    }

    // --- EXPIRABLE ---

    @Test
    void handleExpiredProduct_inStockAndNotExpired_decrementsAndSaves() {
        Product product = new Product(null, 15, 30, ProductType.EXPIRABLE, "Butter",
                LocalDate.now().plusDays(26), null, null);
        when(productRepository.save(product)).thenReturn(product);

        productService.handleExpiredProduct(product);

        assertEquals(29, product.getAvailable());
        verify(productRepository, times(1)).save(product);
        verifyNoInteractions(notificationService);
    }

    @Test
    void handleExpiredProduct_alreadyExpired_sendsExpirationNotificationAndZeroesStock() {
        Product product = new Product(null, 90, 6, ProductType.EXPIRABLE, "Milk", LocalDate.now().minusDays(2),
                null, null);
        when(productRepository.save(product)).thenReturn(product);

        productService.handleExpiredProduct(product);

        assertEquals(0, product.getAvailable());
        verify(notificationService, times(1))
                .sendExpirationNotification(product.getName(), product.getExpiryDate());
        verify(productRepository, times(1)).save(product);
    }

    @Test
    void handleExpiredProduct_expiringToday_isTreatedAsExpired() {
        // GIVEN: expiryDate is exactly today (strict isAfter(now) => false => treated as expired)
        Product product = new Product(null, 0, 5, ProductType.EXPIRABLE, "Milk", LocalDate.now(), null, null);
        when(productRepository.save(product)).thenReturn(product);

        productService.handleExpiredProduct(product);

        assertEquals(0, product.getAvailable());
        verify(notificationService, times(1))
                .sendExpirationNotification(product.getName(), product.getExpiryDate());
        verify(productRepository, times(1)).save(product);
    }

    // --- Dispatcher ---

    @Test
    void processOrderItem_dispatchesByType() {
        Product normal = new Product(null, 5, 3, ProductType.NORMAL, "USB Cable", null, null, null);
        Product expirable = new Product(null, 15, 30, ProductType.EXPIRABLE, "Butter",
                LocalDate.now().plusDays(26), null, null);
        when(productRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        productService.processOrderItem(normal);
        productService.processOrderItem(expirable);

        assertEquals(2, normal.getAvailable());
        assertEquals(29, expirable.getAvailable());
    }
}
