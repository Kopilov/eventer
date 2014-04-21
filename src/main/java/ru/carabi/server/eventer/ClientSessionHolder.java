package ru.carabi.server.eventer;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.internal.ConcurrentSet;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;
import javax.xml.ws.Holder;
import ru.carabi.server.soap.GuestService;
import ru.carabi.server.soap.GuestService_Service;
import ru.carabi.server.soap.CarabiException_Exception;
import ru.carabi.server.soap.CarabiOracleException_Exception;
import ru.carabi.server.soap.JSONException_Exception;
import ru.carabi.server.soap.MessageService;
import ru.carabi.server.soap.MessageService_Service;
import ru.carabi.server.soap.QueryParameter;
import ru.carabi.server.soap.QueryService;
import ru.carabi.server.soap.QueryService_Service;

/**
 * Контейнер клиентских сессий.
 * При подключении клиента к NettyListener соединение сохраняется тут для дальнейшей
 * передачи пакетов по инициативе сервера.
 * 
 * @author sasha<kopilov.ad@gmail.com>
 */
public class ClientSessionHolder {
	private static final String key = "Carab!";
	private static final String salt = "EventerKOD";
	private static final String pepper = "#Test~";
	private static final Charset charset = Charset.forName("UTF-8");
	private static final ResourceBundle settings = ResourceBundle.getBundle("ru.carabi.server.eventer.Settings");
	private static final Logger logger = Logger.getLogger(ClientSessionHolder.class.getName());
	private static GuestService guestServicePort;
	private static MessageService messageServicePort;
	private static QueryService queryServicePort;

	public static void setSoapServer(String soapServer) throws MalformedURLException {
		GuestService_Service guestService = new GuestService_Service(new URL(soapServer + settings.getString("GUEST_SERVICE")));
		guestServicePort = guestService.getPort(GuestService.class);
		MessageService_Service messageService = new MessageService_Service(new URL(soapServer + settings.getString("MESSAGE_SERVICE")));
		messageServicePort = messageService.getPort(MessageService.class);
		QueryService_Service queryService = new QueryService_Service(new URL(soapServer + settings.getString("QUERY_SERVICE")));
		queryServicePort = queryService.getPort(QueryService.class);
	}
	
	private static final ConcurrentHashMap<String, Timer> sessions = new ConcurrentHashMap<>();
	private static final ConcurrentSet<ChannelHandlerContext> channels = new ConcurrentSet<>();
	
	/**
	 * Добавить подключение.
	 * Если пользователь авторизован (токен есть в системе) &mdash; сохраннить сессию,
	 * если нет &mdash; отклонить.
	 * @param eventerToken токен, полученный функцией getEventerToken SOAP-сервера.
	 * @param sessionContextChannel сохраняемая сессия.
	 * @return была ли сохранена сессия.
	 */
	public static boolean addSession(String eventerToken, ChannelHandlerContext sessionContextChannel) {
		try {
			String soapToken = decrypt(eventerToken);
			logger.log(Level.INFO, "{0}", guestServicePort.getOracleUserID(soapToken));
			Timer sessionTimer = new Timer(eventerToken, soapToken, sessionContextChannel);
			sessions.put(eventerToken, sessionTimer);
			channels.add(sessionContextChannel);
			new Thread(sessionTimer).start();
			return true;
		} catch (Exception ex) {
			logger.log(Level.INFO, null, ex);
			return false;
		}
	}
	
	public static boolean channelIsRegistered(ChannelHandlerContext channel) {
		return channels.contains(channel);
	}
	
//	public static ChannelHandlerContext getSession(String token) {
//		return sessions.get(token);
//	}
	public static void delSession(String token) {
		Timer sessionTimer = sessions.remove(token);
		if (sessionTimer != null) {
			sessionTimer.active = false;
			channels.remove(sessionTimer.sessionContextChannel);
		}
	}
	
	private static class Timer implements Runnable {
		boolean active = true;
		String soapToken;
		String eventerToken;
		ChannelHandlerContext sessionContextChannel;
		
		Timer(String eventerToken, String soapToken, ChannelHandlerContext session) {
			active = true;
			this.eventerToken = eventerToken;
			this.soapToken = soapToken;
			this.sessionContextChannel = session;
		}

		@Override
		public synchronized void run() {
			int format = 0;
			while (active && !sessionContextChannel.isRemoved()) {
				try {
					sendEvents(sessionContextChannel, eventerToken, format);
					//sendPing(sessionContextChannel);
					format++;
					format = format % 2;
				} catch (CarabiException_Exception | JSONException_Exception | CarabiOracleException_Exception ex) {
					logger.log(Level.SEVERE, null, ex);
				}
				try {
					wait(5000);
				} catch (InterruptedException ex) {
					active = false;
					logger.log(Level.SEVERE, null, ex);
				}
			}
		}
		
	}
	
	public static void sendPing(ChannelHandlerContext sessionContextChannel) {
		String answer = "PING ПИНГ";
		CarabiMessage.sendAnswer(sessionContextChannel, CarabiMessage.Type.ping.getCode(), answer);
	}
	
	public static void sendEvents(ChannelHandlerContext sessionContextChannel, String token, int format) throws CarabiException_Exception, JSONException_Exception, CarabiOracleException_Exception {
		String answer;
		if (format == 1) {
			answer = messageServicePort.getNotifyMessages(getSoapToken(token));
		} else {
			
			Holder<List<QueryParameter>> parameters = new Holder<>();
			parameters.value = new ArrayList<>();
			QueryParameter days = new QueryParameter();
			days.setName("DAYS");
			days.setValue("1");
			parameters.value.add(days);
			queryServicePort.runStoredQuery(getSoapToken(token), "", "GET_NOTIFY_MESSAGES", -1, true, parameters);
			answer = parameters.value.get(0).getValue();
		}
		CarabiMessage.sendAnswer(sessionContextChannel, CarabiMessage.Type.chatCount.getCode(), answer);
	}
	
	/**
	 * Расшифровка входного токена.
	 * Если входной токен был получен функцией getEventerToken SOAP-сервера &mdash;
	 * на выходе должен получиться SOAP-токен.
	 * @param encrypted зашифрованный токен
	 * @return расшифрованный токен для обращения к SOAP-серверу
	 * @throws Exception 
	 */
	private static String decrypt (String encrypted) throws Exception {
		byte[] input = DatatypeConverter.parseBase64Binary(encrypted);
		String secretKey = key + salt;
		SecretKeySpec key = new SecretKeySpec(secretKey.getBytes(charset), "AES");
		String iv = salt + pepper;
		IvParameterSpec ivSpec = new IvParameterSpec(iv.getBytes(charset));
		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		cipher.init(Cipher.DECRYPT_MODE, key, ivSpec);
		byte[] decrypted = new byte[cipher.getOutputSize(input.length)];
		int dec_len = cipher.update(input, 0, input.length, decrypted, 0);
		dec_len += cipher.doFinal(decrypted, dec_len);
		return new String(decrypted, charset).trim();
	}
	
	/**
	 * Получение SOAP-токена по входному токену Eventer-а для авторизовавшихся пользователей.
	 * Результат должен совпадать с результатом функции decrypt, но предполагается
	 * большая скорость работы (не расшифровка, а чтение из памяти).
	 * @return 
	 */
	private static String getSoapToken(String eventerToken) {
		return sessions.get(eventerToken).soapToken;
	}
	

}
