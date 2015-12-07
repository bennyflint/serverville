package com.dreamwing.serverville.agent;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.dreamwing.serverville.data.AgentKey;
import com.dreamwing.serverville.log.SVLog;
import com.dreamwing.serverville.net.ApiNotFoundException;
import com.dreamwing.serverville.net.HttpRequestInfo;
import com.dreamwing.serverville.net.HttpUtil;
import com.dreamwing.serverville.net.NotAuthenticatedException;
import com.dreamwing.serverville.net.HttpConnectionInfo;
import com.dreamwing.serverville.net.HttpUtil.JsonApiException;
import com.dreamwing.serverville.util.JSON;
import com.dreamwing.serverville.util.SVID;
import com.fasterxml.jackson.core.JsonProcessingException;


import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpHeaders.Names;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;

public class AgentServerConnectionHandler extends SimpleChannelInboundHandler<Object> {

	private static final Logger l = LogManager.getLogger(AgentServerConnectionHandler.class);
	
	private static final String WEBSOCKET_PATH = "/websocket";
	private WebSocketServerHandshaker Handshaker;
	
	private HttpConnectionInfo Info;
	
	private AgentDispatcher Dispatcher;
	
	private volatile boolean WebsocketConnected = false;
	
	
	private int MessageSequence = 0;
	
	public AgentServerConnectionHandler(AgentDispatcher dispatcher)
	{
		super();
		
		Dispatcher = dispatcher;
	}
	
	@Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception
    {
		
		super.channelActive(ctx);
		Info = new HttpConnectionInfo();
		Info.Ctx = ctx;
		Info.ConnectionId = SVID.makeSVID();
		
		l.debug(new SVLog("Agent HTTP connection opened", Info));
    }
	
	@Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		super.channelInactive(ctx);
		WebsocketConnected = false;
		l.debug(new SVLog("Agent HTTP connection closed", Info));
		
    }
	
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, Object msg)
			throws Exception
	{
		ChannelFuture lastWrite = null;
		boolean keepAlive = true;
		
		if (msg instanceof FullHttpRequest)
		{
			FullHttpRequest request = (FullHttpRequest) msg;
			keepAlive = HttpHeaders.isKeepAlive(request);
			lastWrite = handleHttpRequest(ctx, request);
        } else if (msg instanceof WebSocketFrame)
        {
        	lastWrite = handleWebSocketFrame(ctx, (WebSocketFrame) msg);
        }
		
		if (!keepAlive) {
            // Close the connection when the whole content is written out.
    		if(lastWrite != null)
    		{
    			lastWrite.addListener(ChannelFutureListener.CLOSE);
    		}
    		else
    		{
    			ctx.close();
    		}
        }
	}
	
	private boolean authenticate(FullHttpRequest request)
	{
		String authToken = request.headers().get(Names.AUTHORIZATION);
		if(authToken == null)
		{
			return false;
		}
		
		AgentKey key = null;
		try {
			key = AgentKey.load(authToken);
		} catch (SQLException e) {
			l.error("Error loading agent key:", e);
			return false;
		}
		
		if(key == null)
			return false;
		
		if(key.Expiration != null && key.Expiration.getTime() <= System.currentTimeMillis())
			return false;
		
		return true;
	}
	
	private ChannelFuture handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws URISyntaxException, JsonProcessingException
	{
		URI uri = new URI(request.getUri());
    	
		HttpRequestInfo CurrRequest = new HttpRequestInfo();
    	CurrRequest.init(Info, request, SVID.makeSVID());
    	
    	l.debug(new SVLog("Agent HTTP request", CurrRequest));
    	
		if (!request.getDecoderResult().isSuccess()) {
            return HttpUtil.sendError(CurrRequest, "Couldn't decode request", HttpResponseStatus.BAD_REQUEST);
        }
		
		if(!authenticate(request))
		{
			return HttpUtil.sendError(CurrRequest, "Auth not accepted", HttpResponseStatus.FORBIDDEN);
		}
		
		String uriPath = uri.getPath();
		
		if(uriPath.equals(WEBSOCKET_PATH))
		{
			String websockUrl = request.headers().get(Names.HOST) + WEBSOCKET_PATH;
			WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
					websockUrl, null, false);
			
			Handshaker = wsFactory.newHandshaker(request);
			if (Handshaker == null) {
	            return WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
	        } else {
	        	WebsocketConnected = true;
	        	return Handshaker.handshake(ctx.channel(), request);
	        }
		}
		
		if(uriPath.startsWith("/api/"))
		{
			//Invoking an API directly over HTTP
			
			String messageType = uriPath.substring(5);
			String messageBody = null;
			if(request.getMethod() == HttpMethod.POST && request.content() != null)
			{
				messageBody = request.content().toString(StandardCharsets.UTF_8);
			}
		
			
			ByteBuf reply = null;
			try
			{
				reply = Dispatcher.dispatch(messageType, messageBody);
			}
			catch(ApiNotFoundException e)
			{
				return HttpUtil.sendError(CurrRequest, "Thing not found", HttpResponseStatus.NOT_FOUND);
			}
			catch(NotAuthenticatedException e)
			{
				return HttpUtil.sendError(CurrRequest, "Not authenticated", HttpResponseStatus.FORBIDDEN);
			}
			catch(JsonProcessingException e)
			{
				return HttpUtil.sendError(CurrRequest, "Invalid JSON: "+e.getMessage(), HttpResponseStatus.BAD_REQUEST);
			}
			catch(JsonApiException e)
			{
				return HttpUtil.sendErrorJson(CurrRequest, e.Error, HttpResponseStatus.BAD_REQUEST);
			}
			catch(Exception e)
			{
				return HttpUtil.sendError(CurrRequest, "Um", HttpResponseStatus.INTERNAL_SERVER_ERROR);
			}
			

			
			HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
					reply);
			
			if(reply != null)
			{
				HttpUtil.setContentTypeHeader(response, "application/json");
				HttpHeaders.setContentLength(response, reply.readableBytes());
			}
			
			return ctx.writeAndFlush(response);
		}
		
		return HttpUtil.sendError(CurrRequest, "Thing not found", HttpResponseStatus.NOT_FOUND);
	}
	
	private ChannelFuture handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame)
	{
		// Check for closing frame
        if (frame instanceof CloseWebSocketFrame) {
        	return Handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
        }
        else if (frame instanceof PingWebSocketFrame) {
            return ctx.channel().write(new PongWebSocketFrame(frame.content().retain()));
        }
        else if (frame instanceof TextWebSocketFrame)
        {
        	TextWebSocketFrame textFrame = (TextWebSocketFrame)frame;
        	String messageText = textFrame.text();
        	return handleTextMessage(messageText);
        }
        else
        {
        	throw new UnsupportedOperationException(String.format("%s frame types not supported", frame.getClass()
                    .getName()));
        }
	}
	
	private ChannelFuture handleTextMessage(String messageText)
	{
		String messageParts[] = messageText.split(":", 3);
		if(messageParts.length != 3)
		{
			l.debug("Incorrectly formatted message: "+messageText);
			return null;
		}
		
		String messageType = messageParts[0];
		String messageNum = messageParts[1];
		String messageBody = messageParts[2];
		
		String reply = null;
		try {
			if(messageType == null)
			{
				// It's a reply to a sever message
				
			}
			else
			{
				reply = Dispatcher.dispatch(messageType, messageNum, messageBody);
			}
		} catch (ApiNotFoundException e) {
			l.error("Unknown message type: "+messageType, e);
			return null;
		} catch (Exception e) {
			l.error("Error in message handler: "+messageType, e);
			return null;
		}
		
		if(reply != null)
		{
			return write(reply);
		}
		
		return null;
	}
	
	@Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }
	
	@Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
	
	private synchronized int getNextMessageNum()
	{
		return MessageSequence++;
	}
	
	public ChannelFuture sendMessage(String messageType, Object messageBody) throws Exception
	{
		int messageNum = getNextMessageNum();
		String messageStr = messageType+":"+messageNum+":"+JSON.serializeToString(messageBody);
		
		return write(messageStr);
	}
	
	public ChannelFuture sendMessage(String messageType, String serializedMessageBody)
	{
		int messageNum = getNextMessageNum();
		String messageStr = messageType+":"+messageNum+":"+serializedMessageBody;
		
		return write(messageStr);
	}
	
	private ChannelFuture write(String data)
	{
		if(!WebsocketConnected)
			return null;
		
		ChannelFuture future = Info.Ctx.channel().write(new TextWebSocketFrame(data));
		Info.Ctx.channel().flush();
		return future;
	}
	
	

}
