# JReticulum — Java Implementation of Reticulum Protocol / Реализация Reticulum на Java

> ⚙️ Java-реализация [Reticulum](https://github.com/markqvist/Reticulum) — децентрализованного сетевого стека для автономных mesh-сетей. Поддерживает шифрование, маршрутизацию, announce, работу через (TCP, local, backbone).
> 
> ⚙️ Java implementation of [Reticulum](https://github.com/markqvist/Reticulum) — decentralized network stack for offline-first mesh networks. Supports encryption, routing, service discovery, and multiple interfaces (TCP, local, backbone).

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Java 11+](https://img.shields.io/badge/Java-11%2B-orange)
![Maven Build](https://img.shields.io/badge/build-Maven-informational)

---

## 📌 Что такое Reticulum? / What is Reticulum?

[Reticulum](https://markqvist.github.io/Reticulum/manual/) — это защищённый, децентрализованный протокол связи, разработанный Mark Qvist. Он позволяет строить **P2P-сети без интернета**, используя любые двунаправленные каналы: Wi-Fi, LoRa, BLE, Serial, TCP и др.

JReticulum — это **чистая Java-реализация** этого протокола, подходящая для:

- Android-приложений
- Серверных приложений
- IoT-устройств
- Систем экстренной связи

[Reticulum](https://markqvist.github.io/Reticulum/manual/) is a secure, decentralized communication protocol developed by Mark Qvist. It allows you to build **P2P networks without the internet** using any bidirectional channels: Wi-Fi, LoRa, BLE, Serial, TCP, and more. 

JReticulum is a **pure Java implementation** of this protocol, suitable for:

- Android applications
- Server applications
- IoT devices
- Emergency communication systems
---

## 🧱 Возможности / Features

✅ **Реализовано:**

- Полная система пакетов (`Packet`, `DataPacket`, `TPacket`)
  Packet system with headers, context, flags
- Криптография:Cryptography:
  - Curve25519 (Bouncy Castle)
  - Fernet (com.macasaet.fernet-java8)
  - SHA-256, HMAC
- Интерфейсы:
  - TCP (`TCPServerInterface`, `TCPClientInterface`)
  - Локальные соединения (`LocalServerInterface`, `LocalClientInterface`)
  - Автообнаружение (`AutoInterface`)
  - Backbone-тоннели (удалённое подключение)
- Транспортный слой:
  - Маршрутизация
  - Announce и распространение путей
  - Обработка ресурсов
- Хранение:
  - Nitrite (встроенная NoSQL БД)
  - Файловое хранилище (`storage/`)
- Конфигурация:
  - YAML (`snakeyaml`)
  - Поддержка `config.yml`
- Сериализация:
  - Jackson (JSON/YAML)
  - MessagePack (`jackson-dataformat-msgpack`)
  - JBBP (для бинарных заголовков)
- Сетевой ввод/вывод:
  - Netty (через `netty-all`)
- Логирование:
  - Log4j2 + SLF4J
- Адресация:
  - IP-адреса через `ipaddress` (поддержка IPv4/IPv6)

✅ **Implemented:**

- Full packet system (`Packet`, `DataPacket`, `TPacket`)
  Packet system with headers, context, flags
- Cryptography:
  - Curve25519 (Bouncy Castle)
  - Fernet (com.macasaet.fernet-java8)
  - SHA-256, HMAC
- Interfaces:
  - TCP (`TCPServerInterface`, `TCPClientInterface`)
  - Local connections (`LocalServerInterface`, `LocalClientInterface`)
  - Auto-discovery (`AutoInterface`)
  - Backbone tunnels (remote connection)
- Transport layer:
  - Routing
  - Announce and propagate paths
  - Resource handling
- Storage:
  - Nitrite (built-in NoSQL DB)
  - File storage (`storage/`)
- Configuration:
  - YAML (`snakeyaml`)
  - Support for `config.yml`
- Serialization:
  - Jackson (JSON/YAML)
  - MessagePack (`jackson-dataformat-msgpack`)
  - JBBP (for binary headers)
- Network I/O:
  - Netty (via `netty-all`)
- Logging:
  - Log4j2 + SLF4J
- Addressing:
  - IP addresses via `ipaddress` (IPv4/IPv6 support)
---

## 🚀 Быстрый старт / Quick Start

### 1. Добавьте зависимость / Add to `pom.xml`

```xml
<dependency>
    <groupId>io.reticulum</groupId>
    <artifactId>reticulum-network-stack</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### или соберите локально / or build locally

   ```bash 
git clone https://github.com/yourname/reticulum-network-stack.git
cd reticulum-network-stack
mvn clean install
   ````
### 2. Запустите сервер / Start Server

   ```java
   // examples/ReticulumServer.java
package examples;

import io.reticulum.Reticulum;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.ProofStrategy;
import io.reticulum.interfaces.tcp.TCPServerInterface;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ReticulumServer {
    private static final Logger log = LogManager.getLogger(ReticulumServer.class);

    public static void main(String[] args) {
        try {
            Reticulum reticulum = new Reticulum();
            reticulum.start();

            TCPServerInterface serverIf = new TCPServerInterface("server", "0.0.0.0", 4242);
            reticulum.addInterface(serverIf);

            Destination service = reticulum.registerDestination(
                    "example.service",
                    DestinationType.SINGLE,
                    ProofStrategy.FULL
            );
            service.announce();

            log.info("✅ Сервер запущен. Ожидаем пакеты...");

            // Обработка входящих пакетов
            service.setIncomingPacketCallback(packet -> {
                log.info("📥 Получено: {}", new String(packet.getData()));

                var response = new Packet(packet.getSender(), "Ответ от сервера!".getBytes());
                response.send();
            });

            Thread.currentThread().join(); // keep alive

        } catch (Exception e) {
            log.error("Ошибка сервера", e);
        }
    }
}
   ```

### 3. Подключитесь с клиента / Connect from Client

   ```java
// examples/ReticulumClient.java
package examples;

import io.reticulum.Reticulum;
import io.reticulum.Resolver;
import io.reticulum.destination.Destination;
import io.reticulum.interfaces.tcp.TCPClientInterface;
import io.reticulum.packet.Packet;

public class ReticulumClient {
    public static void main(String[] args) {
        try {
            Reticulum reticulum = new Reticulum();
            reticulum.start();

            TCPClientInterface clientIf = new TCPClientInterface("client", "127.0.0.1", 4242);
            reticulum.addInterface(clientIf);

            Resolver resolver = new Resolver(reticulum);
            Destination remote = resolver.waitForAnnounce("example.service");

            if (remote != null) {
                System.out.println("📡 Найден сервис: " + remote.getHash());

                Packet packet = new Packet(remote.getHash(), "Привет от клиента!".getBytes());
                packet.send();

                System.out.println("📤 Пакет отправлен!");
            }

            Thread.currentThread().join();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```
---
### 🛠 Требования / Requirements

- Java 11+
- Maven 3.6+
- Для Android: API 24+ (рекомендуется)
- Разрешения (если используется Wi-Fi/BLE): CHANGE_WIFI_STATE, BLUETOOTH

### 📁 Структура проекта / Project Structure

```
src/main/java/io/reticulum/
├── Reticulum.java              — основной класс / the main class
├── Transport.java              — транспортный слой / transport layer
├── destination/                — точки назначения / destination points
├── identity/                   — ключи и идентичности / keys and identities
├── interfaces/                 — сетевые интерфейсы / network interfaces
├── packet/                     — пакеты и сериализация / packages and serialization
├── resource/                   — передача файлов / file transfer
├── storage/                    — хранение (Nitrite + файлы) / storage (Nitrite + files)
├── utils/                      — вспомогательные утилиты / auxiliary utilities
└── config/                     — загрузка config.yml / loading config.yml
```

### 📄 Лицензия / License

[MIT](http://www.opensource.org/licenses/mit-license.php)

### 🤝 Участие / Contributing

Приветствуются:

- Баг-репорты
- Фичи
- Документация
- Примеры
- Создавайте вопросы или пул-реквесты!

Welcome to:

- Bug reports
- Features
- Documentation
- Examples
- Create questions or pull requests!

### 📚 Полезные ссылки / Links

- [Reticulum](https://reticulum.network/) Manual (EN)
- [LXMF](https://github.com/markqvist/LXMF) Specification (EN)
- GitHub: [JReticulum](https://github.com/sergst83/reticulum-network-stack)

Создано с ❤️ для децентрализованной связи. \
Built with ❤️ for decentralized communication.
