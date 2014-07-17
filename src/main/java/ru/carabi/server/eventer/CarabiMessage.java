package ru.carabi.server.eventer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonReader;
import javax.xml.bind.DatatypeConverter;
import javax.xml.ws.Holder;
import ru.carabi.server.soap.CarabiException_Exception;
import ru.carabi.server.soap.CarabiOracleException_Exception;
import ru.carabi.server.soap.QueryParameter;

/**
 * Сообщение от клиента Carabi.
 * Полиморфный класс, обрабатывающий входящие сигналы и генерирующий ответы.
 * @author sasha<kopilov.ad@gmail.com>
 */
public class CarabiMessage {
	static final Logger logger = Logger.getLogger(CarabiMessage.class.getName());
	public enum Type {
		reserved(0),
		ping(1),
		pong(2),
		auth(3),
		synch(4), //запросить события от сервера
		autosynch(5), //включить автоматическое получение событий
		disableSync(6),
		disableSyncAll(7),
		fireEvent(8),
		shutdown(9),
		baseEventsTable(10),
		baseEventsList(11),
		textMessage(12),
		error(Short.MAX_VALUE);
		
		private short code;
		Type (int code) {
			if (code != (short)code) {
				throw new IllegalArgumentException("Too big code: " + code);
			}
			this.code = (short)code;
		}
		
		public short getCode () {
			return code;
		}
		
		private final static Map<Short, Type> codeValueMap = new ConcurrentHashMap<>(16);
		
		static {
			for (Type type: Type.values()) {
				codeValueMap.put(type.code, type);
			}
		}
		public static Type getTypeByCode(short codeValue) {
			return codeValueMap.get(codeValue);
		}
	}
	
	private final Type type;
	private final String text;
	private final ChannelHandlerContext ctx;
	private final MessagesHandler client;
	
	/**
	 * Фабрика входящих сообщений.
	 * @param message Строка сообщения
	 * @param currentMessageType Код типа сообщенияи
	 * @param ctx Канал сессии
	 * @return формализованное сообщение для обработки.
	 */
	static CarabiMessage readCarabiMessage(String message, Type messageType, MessagesHandler client) {
		switch (messageType) {
			case ping:
				return new Ping(message, messageType, client);
			case pong:
				return new Pong(message, messageType, client);
			case auth:
				return new Auth(message, messageType, client);
			case synch: 
				return new Sync(message, messageType, client);
			case autosynch:
				return new Autosynch(message, messageType, client);
			case disableSync:
				return new DisableSync(message, messageType, client);
			case disableSyncAll:
				return new DisableSyncAll(message, messageType, client);
			case fireEvent:
				return new FireEvent(message, messageType, client);
			case shutdown:
				return new Shutdown(message, messageType, client);
			default:
				return new CarabiMessage(message, messageType, client);
		}
	}
	
	/**
	 * Фабрика исходящих сообщений.
	 * @param prevMessage Предыдущее сообщение или на которое отвечаем (если не генерируем событие сами)
	 * @param currentMessageType Код типа сообщения
	 * @param force обязательно отправлять сообщение, даже если ошибка или нет новых событий
	 * @return формализованное сообщение для отправки.
	 */
	static CarabiMessage writeCarabiMessage(String prevMessage, Type messageType, boolean force, MessagesHandler client) {
		switch (messageType) {
			case ping:
				return new Ping(null, messageType, client);
			case baseEventsTable:
				return new BaseEventsTable(prevMessage, messageType, force, client);
			case baseEventsList:
				return new BaseEventsList(prevMessage, messageType, force, client);
			case textMessage:
				
			default:
				return new CarabiMessage(prevMessage, messageType, client);
		}
	}
	public CarabiMessage(String text, Type type, MessagesHandler client) {
		this.text = text;
		this.type = type;
		this.ctx = client.getChannel();
		this.client = client;
	}

	public ChannelHandlerContext getCtx() {
		return ctx;
	}
	
	
	public MessagesHandler getClient() {
		return client;
	}

	public String getText() {
		return text;
	}
	
	public Type getType() {
		return type;
	}
	
	/**
	 * Обработка пришедшего сообщения (возможно, с созданием ответа).
	 * @param token токен подключившегося клиента
	 */
	public void handle(String token) {
		String answer = "Got the message: " + text + " " + type.name();
		sendMessage(ctx, Type.reserved, answer);
	}
	
	/**
	 * Создание сообщения по инициативе сервера.
	 * @param token токен подключившегося клиента
	 */
	public void post(String token) {
		sendMessage(ctx, CarabiMessage.Type.reserved, text);
	}
	
	/**
	 * Отправка сообщения.
	 * Отправка кода и текста сообщения с терминальным нулём в канал, переданный методу {@link readCarabiMessage}
	 * @param sessionContextChannel
	 * @param type тип отправляемого сообщения
	 * @param answer текст отправляемого сообщения
	 */
	protected static void sendMessage(ChannelHandlerContext sessionContextChannel, Type type, String answer) {
		short code = type.getCode();
		sendMessage(sessionContextChannel, code, answer);
	}
	protected static void sendMessage(ChannelHandlerContext sessionContextChannel, short code, String answer) {
		logger.fine(answer);
		byte[] dataToPost = answer.getBytes(Charset.forName("UTF-8"));
		ByteBuf buffer = sessionContextChannel.alloc().buffer(dataToPost.length + 3);
		buffer.writeShort(code);
		buffer.writeBytes(dataToPost);
		buffer.writeByte(0);
		sessionContextChannel.writeAndFlush(buffer);
	}
	
	protected Collection<CarabiMessage.Type> parseMessageTypes(String messageTypesJson) {
		JsonReader eventsData = Json.createReader(new StringReader(messageTypesJson));
		JsonArray eventsToSend = eventsData.readArray();
		Collection<Type> types = new ArrayList<>();
		for (int i=0, n=eventsToSend.size(); i<n; i++) {
			types.add(Type.getTypeByCode((short)eventsToSend.getInt(i)));
		}
		return types;
	}
}

class Shutdown extends CarabiMessage {
	public Shutdown(String text, Type type, MessagesHandler client) {
		super(text, type, client);
	}
	@Override
	public void handle(String token) {
		//отключение должно производиться в два этапа: получение ключа и отправка пакета с зашифрованным ключом.
		String encryptedKey = getText();
		if (encryptedKey == null || encryptedKey.equals("")) {
			int keySize = 64;
			byte[] bytes = new byte[keySize];
			for (int i=0; i<keySize; i++) {
				bytes[i] = (byte) Math.round(Math.random() * 127);
			}
			String key = DatatypeConverter.printBase64Binary(bytes);
			getClient().getUtilProperties().setProperty("shutdownKey", key);
			sendMessage(getCtx(), CarabiMessage.Type.shutdown, key);
		} else {
			try {
				String key = ClientsHolder.decrypt(encryptedKey);
				if (key.equals(getClient().getUtilProperties().getProperty("shutdownKey"))) {
					sendMessage(getCtx(), CarabiMessage.Type.shutdown, "shutdownOK");
					Main.shutdown();
				}
			} catch (Exception e) {
				
			}
		}
	}
}

class Ping extends CarabiMessage {
	public Ping(String src, Type type, MessagesHandler client) {
		super("PING ПИНГ", type, client);
	}
	@Override
	public void handle(String token) {
		String answer = "PONG ПОНГ";
		sendMessage(getCtx(), Type.pong, answer);
	}
	
	@Override
	public void post(String token){
		sendMessage(getCtx(), Type.ping, getText());
	}
}

class Pong extends CarabiMessage {
	public Pong(String src, Type type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(String token) {
		final Properties utilProlerties = getClient().getUtilProperties();
		//не отвечаем, если шла проверка сессии -- передаём на Glassfish
		if ("true".equals(utilProlerties.getProperty("testingSession"))) {
			utilProlerties.setProperty("testingSession", "false");
			try {
				SoapGateway.guestServicePort.getUserInfo(getClient().getUtilProperties().getProperty("soapToken"));
			} catch (CarabiException_Exception ex) {
				Logger.getLogger(Pong.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
	}
}

class Auth extends CarabiMessage {
	public Auth(String src, Type type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(String token) {
		if (ClientsHolder.clientlIsRegistered(token)) {
			return;
		}
		if (ClientsHolder.addClient(token, getClient())) {
			String answer = "Клиент " + token + " авторизован!";
			sendMessage(getCtx(), Type.auth, answer);
		} else {
			getCtx().disconnect();
		}
	}
}

class Sync extends CarabiMessage {
	public Sync(String src, Type type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(String token) {
		if (ClientsHolder.clientlIsRegistered(token)) {
			Collection<Type> eventsToSend = parseMessageTypes(getText());
			for (Type type: eventsToSend) {
				CarabiMessage answer = writeCarabiMessage("", type, true, getClient());
				answer.post(token);
			}
		}
	}
}

class Autosynch extends CarabiMessage {
	public Autosynch(String src, Type type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(String token) {
		if (ClientsHolder.clientlIsRegistered(token)) {
			Collection<CarabiMessage.Type> types = parseMessageTypes(getText());
			ClientsHolder.addEventTypes(token, types);
		}
	}
}

class DisableSync extends CarabiMessage {
	public DisableSync(String src, Type type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(String token) {
		if (ClientsHolder.clientlIsRegistered(token)) {
			Collection<CarabiMessage.Type> types = parseMessageTypes(getText());
			ClientsHolder.removeEventTypes(token, types);
		}
	}
}

class DisableSyncAll extends CarabiMessage {
	public DisableSyncAll(String src, Type type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(String token) {
		if (ClientsHolder.clientlIsRegistered(token)) {
			ClientsHolder.clearEventTypes(token);
		}
	}
}

class FireEvent extends CarabiMessage {
	public FireEvent(String src, Type type, MessagesHandler client) {
		super(src, type, client);
	}
	@Override
	public void handle(String token) {
		try {
			ClientsHolder.fireEvent(getText());
		} catch (Exception ex) {
			Logger.getLogger(FireEvent.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
}

class BaseEventsTable extends CarabiMessage {
	boolean force;
	public BaseEventsTable(String src, Type type, boolean force, MessagesHandler client) {
		super(src, type, client);
		this.force = force;
	}
	@Override
	public void post(String token) {
		try {
			String answer;
			Holder<List<QueryParameter>> parameters = new Holder<>();
			parameters.value = new ArrayList<>();
			QueryParameter days = new QueryParameter();
			days.setName("DAYS");
			days.setValue("1");
			parameters.value.add(days);
			SoapGateway.queryServicePort.runStoredQuery(ClientsHolder.getSoapToken(token), "", "GET_NOTIFY_MESSAGES", -1, true, parameters);
			answer = parameters.value.get(0).getValue();
			if (force || !answer.equals(getText())) {
				sendMessage(getCtx(), getType(), answer);
			}
			ClientsHolder.setLastEvent(token, getType(), answer);
		} catch (CarabiException_Exception | CarabiOracleException_Exception ex) {
			Logger.getLogger(BaseEventsTable.class.getName()).log(Level.SEVERE, null, ex);
			if (force) {
				sendMessage(getCtx(), Type.error, ex.getMessage());
			}
		}
	}
	
}
class BaseEventsList extends CarabiMessage {
	boolean force;
	public BaseEventsList(String src, Type type, boolean force, MessagesHandler client) {
		super(src, type, client);
		this.force = force;
	}
	//answer = messageServicePort.getNotifyMessages(getSoapToken(token));
	@Override
	public void post(String token) {
		String answer;
		try {
			answer = SoapGateway.messageServicePort.getNotifyMessages(ClientsHolder.getSoapToken(token));
			if (force || !answer.equals(getText())) {
				sendMessage(getCtx(), getType(), answer);
			}
			ClientsHolder.setLastEvent(token, getType(), answer);
		} catch (CarabiException_Exception | CarabiOracleException_Exception ex) {
			Logger.getLogger(BaseEventsList.class.getName()).log(Level.SEVERE, null, ex);
			sendMessage(getCtx(), Type.error, ex.getMessage());
		}
	}
}

