package vn.tamtd.bot.exchange;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

class FilterUtilTest {

    private static final SymbolFilter BTC = new SymbolFilter(
            "BTCUSDT", "BTC", "USDT", 8, 8,
            new BigDecimal("0.00001"),  // lotMin
            new BigDecimal("9000"),      // lotMax
            new BigDecimal("0.00001"),   // lotStep
            new BigDecimal("0.01"),      // priceMin
            new BigDecimal("1000000"),   // priceMax
            new BigDecimal("0.01"),      // priceTick
            new BigDecimal("10")          // minNotional
    );

    @Test
    void roundQtyDown_respects_step() {
        BigDecimal rounded = FilterUtil.roundQtyDown(new BigDecimal("0.1234567"), BTC);
        assertThat(rounded).isEqualByComparingTo("0.12345");
    }

    @Test
    void roundQtyDown_returns_null_below_lotMin() {
        BigDecimal tiny = FilterUtil.roundQtyDown(new BigDecimal("0.000001"), BTC);
        assertThat(tiny).isNull();
    }

    @Test
    void qtyFromQuote_gives_enough_to_meet_minNotional() {
        BigDecimal price = new BigDecimal("50000");
        BigDecimal qty = FilterUtil.qtyFromQuote(new BigDecimal("15"), price, BTC);
        assertThat(qty).isNotNull();
        assertThat(qty.multiply(price)).isGreaterThanOrEqualTo(BTC.minNotional());
    }

    @Test
    void qtyFromQuote_returns_null_when_below_minNotional() {
        BigDecimal price = new BigDecimal("50000");
        BigDecimal qty = FilterUtil.qtyFromQuote(new BigDecimal("5"), price, BTC);
        assertThat(qty).isNull();
    }

    @Test
    void roundPrice_up_respects_tick() {
        BigDecimal p = FilterUtil.roundPrice(new BigDecimal("12345.678"), BTC, RoundingMode.UP);
        assertThat(p).isEqualByComparingTo("12345.68");
    }

    @Test
    void meetsMinNotional_false_when_below() {
        assertThat(FilterUtil.meetsMinNotional(
                new BigDecimal("0.0001"),
                new BigDecimal("50000"),
                BTC)).isFalse();
    }
}
