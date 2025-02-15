package org.cloudfoundry.multiapps.controller.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CachedObjectTest {

    @Test
    void testCaching() {
        @SuppressWarnings("unchecked")
        Supplier<String> refreshFunction = Mockito.mock(Supplier.class);
        Mockito.when(refreshFunction.get())
               .thenReturn("a", "b");

        CachedObject<String> cachedName = new CachedObject<>(Duration.ZERO);

        assertEquals("a", cachedName.getOrRefresh(refreshFunction));
        assertEquals("b", cachedName.getOrRefresh(refreshFunction));
        assertEquals("b", cachedName.get());
        assertTrue(cachedName.isExpired());
    }

    @Test
    void testExpiration() {
        CachedObject<String> cache = new CachedObject<>("a", Duration.ofMillis(1));

        assertFalse(cache.isExpired(System.currentTimeMillis() - 1000));
        assertTrue(cache.isExpired(System.currentTimeMillis() + 3000));
    }
}
