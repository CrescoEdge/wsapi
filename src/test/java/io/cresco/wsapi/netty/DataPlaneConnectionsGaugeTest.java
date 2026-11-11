package io.cresco.wsapi.netty;

import io.cresco.library.plugin.PluginBuilder;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * wsapi.dataplane.connections counts the dataplane connections that are open. The router adds the dataplane handler
 * during the WebSocket upgrade, on a channel that is already active, so the handler's channelActive never ran and the
 * gauge read 0 whatever was connected (the tests before this one built the channel with the handler in it, where
 * channelActive does run).
 */
class DataPlaneConnectionsGaugeTest {

    private static DataPlaneWsHandler handler() throws Exception {
        ReviewApiDataplaneTest.Recorder rec = new ReviewApiDataplaneTest.Recorder();
        PluginBuilder plugin = ReviewApiDataplaneTest.plugin(rec);
        List<Object[]> rpcs = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        return new DataPlaneWsHandler(plugin, ReviewApiDataplaneTest.principal("tenant-a", "bob", "user"),
                ReviewApiDataplaneTest.gate(plugin, rpcs));
    }

    @Test
    void aHandlerAddedToAnActiveChannelIsCountedAndTakenOutWhenItCloses() throws Exception {
        int before = DataPlaneWsHandler.ACTIVE_CONNECTIONS.get();
        EmbeddedChannel a = new EmbeddedChannel();              // active before the handler is there, as after an upgrade
        a.pipeline().addLast(handler());
        assertEquals(before + 1, DataPlaneWsHandler.ACTIVE_CONNECTIONS.get());
        EmbeddedChannel b = new EmbeddedChannel();
        b.pipeline().addLast(handler());
        assertEquals(before + 2, DataPlaneWsHandler.ACTIVE_CONNECTIONS.get());
        a.close().sync();
        assertEquals(before + 1, DataPlaneWsHandler.ACTIVE_CONNECTIONS.get());
        b.close().sync();
        assertEquals(before, DataPlaneWsHandler.ACTIVE_CONNECTIONS.get());
    }

    @Test
    void aConnectionIsCountedOnceHoweverItsHandlerHearsOfIt() throws Exception {
        int before = DataPlaneWsHandler.ACTIVE_CONNECTIONS.get();
        EmbeddedChannel ch = new EmbeddedChannel(handler());    // here both handlerAdded and channelActive run
        assertEquals(before + 1, DataPlaneWsHandler.ACTIVE_CONNECTIONS.get());
        ch.close().sync();
        assertEquals(before, DataPlaneWsHandler.ACTIVE_CONNECTIONS.get());
    }
}
