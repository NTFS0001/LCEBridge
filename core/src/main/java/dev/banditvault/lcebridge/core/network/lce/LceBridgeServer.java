package dev.banditvault.lcebridge.core.network.lce;

import dev.banditvault.lcebridge.core.BridgeConfig;
import dev.banditvault.lcebridge.core.session.LceBridgeSession;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens for LCE client TCP connections and sets up the Netty pipeline.
 */
public class LceBridgeServer {
    private static final Logger log = LoggerFactory.getLogger(LceBridgeServer.class);

    public static final AttributeKey<LceBridgeSession> SESSION_KEY =
        AttributeKey.valueOf("lce_bridge_session");

    private final BridgeConfig   config;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public LceBridgeServer(BridgeConfig config) {
        this.config = config;
    }

    @SuppressWarnings("deprecation")
    public void start() throws InterruptedException {
        bossGroup   = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
         .channel(NioServerSocketChannel.class)
         .childOption(ChannelOption.TCP_NODELAY, true)
         .childOption(ChannelOption.SO_KEEPALIVE, true)
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 ChannelPipeline p = ch.pipeline();
                 p.addLast("decoder", new LcePacketDecoder());
                 p.addLast("encoder", new LcePacketEncoder());
                 p.addLast("handler", new LceChannelHandler(config)); // fresh per connection
             }
         });

        serverChannel = b.bind(config.lceAddress, config.lcePort).sync().channel();
        log.info("LCEBridge listening on {}:{}", config.lceAddress, config.lcePort);
    }

    public void shutdown() {
        log.info("Shutting down LCEBridge...");
        if (serverChannel != null) serverChannel.close();
        if (bossGroup   != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
    }
}
