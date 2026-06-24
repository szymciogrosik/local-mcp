package com.codefromheaven.mcp.config;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

/**
 * Configuration for the underlying Netty Web Server.
 * <p>
 * <b>Why is this needed?</b>
 * When running this application inside Docker (especially on Windows with WSL2), the Docker virtual NAT
 * aggressively drops TCP connections that have been idle for a short period (typically ~5 minutes).
 * Because the MCP protocol relies on long-lived Server-Sent Events (SSE) connections, periods of
 * inactivity from the user cause the underlying TCP connection to be silently severed by the NAT.
 * The client (e.g. OpenCode) is unaware of this drop and times out when attempting to send new requests.
 * <p>
 * Standard L4 TCP Keep-Alive settings (via sysctls) are often ignored or mishandled by the WSL2 networking stack.
 * To guarantee connection stability, this configuration implements an aggressive L7 (Application Layer) heartbeat.
 * It injects an {@link IdleStateHandler} into the Netty pipeline that monitors for write-idle periods.
 * If no data is sent for 30 seconds, it forcibly writes a harmless SSE comment frame ({@code ":\n\n"})
 * directly to the byte buffer. This ensures continuous physical data flow over the connection,
 * completely bypassing Docker's idle connection timeout mechanisms.
 */
@Configuration
public class NettyConfiguration {

    @Bean
    public WebServerFactoryCustomizer<NettyReactiveWebServerFactory> nettyCustomizer() {
        return factory -> factory.addServerCustomizers(httpServer ->
            httpServer.doOnConnection(connection -> {
                connection.addHandlerLast(new IdleStateHandler(0, 30, 0));
                connection.addHandlerLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                        if (evt instanceof IdleStateEvent) {
                            ByteBuf buf = ctx.alloc().buffer();
                            buf.writeBytes(":\n\n".getBytes(StandardCharsets.UTF_8));
                            ctx.writeAndFlush(buf);
                        } else {
                            super.userEventTriggered(ctx, evt);
                        }
                    }
                });
            })
        );
    }
}
