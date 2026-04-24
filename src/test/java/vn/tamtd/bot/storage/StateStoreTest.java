package vn.tamtd.bot.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StateStoreTest {

    @Test
    void load_when_file_missing_returns_fresh(@TempDir Path tmp) {
        StateStore store = new StateStore(tmp);
        BotState state = store.load();
        assertThat(state).isNotNull();
        assertThat(state.v0).isZero();
        assertThat(state.positions).isEmpty();
    }

    @Test
    void save_then_load_roundtrips(@TempDir Path tmp) {
        StateStore store = new StateStore(tmp);
        BotState state = BotState.fresh();
        state.v0 = 1000;
        state.v0SnapshotAt = Instant.parse("2026-04-22T00:00:00Z");
        state.reserveFund = 200;
        state.positions.put("BTCUSDT",
                Position.newEntry("BTCUSDT",
                        new BigDecimal("0.01"),
                        new BigDecimal("45000"),
                        "WATCHLIST",
                        5.0, -3.0));
        store.save(state);

        BotState loaded = store.load();
        assertThat(loaded.v0).isEqualTo(1000);
        assertThat(loaded.reserveFund).isEqualTo(200);
        assertThat(loaded.positions).containsKey("BTCUSDT");
        assertThat(loaded.positions.get("BTCUSDT").entryPrice).isEqualByComparingTo("45000");
    }

    @Test
    void load_corrupt_file_returns_fresh(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("state.json"), "{not valid json");
        StateStore store = new StateStore(tmp);
        BotState state = store.load();
        assertThat(state).isNotNull();
        assertThat(state.v0).isZero();
    }
}
