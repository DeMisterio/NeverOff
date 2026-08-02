# Промпт для AI-агента: разработка Android-мессенджера "NeverOff"

> Скопируйте весь текст ниже и передайте его AI-агенту (Claude Code, Cursor, и т.д.) как техническое задание на реализацию проекта.

---

## Роль

Ты — старший Android-разработчик и архитектор бэкенда. Твоя задача — спроектировать и реализовать прототип (v1) мобильного мессенджера **NeverOff** под Android: нативное приложение на Kotlin с бэкендом на Supabase (PostgreSQL + Realtime + Storage + Auth).

## Общая идея продукта

NeverOff — минималистичный защищённый мессенджер. Ключевые принципы:
- Приватность: сервер не должен иметь доступа к открытому тексту сообщений (zero-knowledge backend).
- Минимализм: ничего лишнего в интерфейсе, фокус на переписке.
- Быстрый старт: регистрация только по email-коду, без пароля.

---

## 1. Технологический стек

- **Клиент:** Kotlin, Jetpack Compose, Material 3 (кастомная тема), Room (локальная БД), Coroutines/Flow.
- **Локальное шифрованное хранилище:** Room + **SQLCipher** (шифрование файла БД целиком) + **Android Keystore** для хранения мастер-ключей и приватных ключей пользователя.
- **Бэкенд:** Supabase — Postgres (основная БД), **Supabase Realtime** (подписка на новые сообщения через Postgres CDC/websocket), **Supabase Storage** (аватарки), **Supabase Auth** (email OTP) или Edge Function + сторонний бесплатный email-сервис.
- **Криптография:** javax.crypto / Google Tink (не писать AES-режимы вручную) для реализации схемы, описанной в разделе 4.

---

## 1.1 Переменные окружения (уже подготовлены — не спрашивай их у меня)

Ключи и URL уже лежат в файле `.env` в корне проекта на момент начала работы. **Не проси предоставить их в чате и не хардкодь значения в коде** — читай их из `.env`.

Ожидаемые ключи (шаблон `.env.example` уже в проекте):

```
SUPABASE_URL=
SUPABASE_PUBLISHABLE_KEY=
SUPABASE_SERVICE_ROLE_KEY=
SUPABASE_DB_URL=
RESEND_API_KEY=
BREVO_API_KEY=
APP_ENVIRONMENT=
```

Обязательные шаги:
1. Добавь `.env` в `.gitignore` (файл не должен попасть в git), оставь `.env.example` с пустыми значениями как есть.
2. Android нативно `.env` не читает — пробрось значения в `BuildConfig` на этапе сборки в `app/build.gradle.kts`:

```kotlin
import java.util.Properties

val envFile = rootProject.file(".env")
val envProps = Properties().apply {
    if (envFile.exists()) envFile.inputStream().use { load(it) }
}

android {
    defaultConfig {
        buildConfigField("String", "SUPABASE_URL", "\"${envProps["SUPABASE_URL"] ?: ""}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${envProps["SUPABASE_PUBLISHABLE_KEY"] ?: ""}\"")
    }
    buildFeatures { buildConfig = true }
}
```

3. `SUPABASE_SERVICE_ROLE_KEY`, `SUPABASE_DB_URL`, `RESEND_API_KEY`/`BREVO_API_KEY` — секретные. Используй их только при первичной настройке проекта (прогон SQL-миграций, Edge Functions), но **никогда** не пробрасывай их в `BuildConfig` Android-приложения — туда идут только `SUPABASE_URL` и `SUPABASE_PUBLISHABLE_KEY`.
4. Заполнено только одно из `RESEND_API_KEY` / `BREVO_API_KEY` (то, что выбрано для отправки OTP-кодов) — второе оставь пустым и не используй.

---

## 2. Схема базы данных (Postgres)

```sql
-- Профили пользователей
create table profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text unique not null,
  display_name text,
  bio text,
  avatar_url text,
  public_key text not null,      -- публичный ключ для E2E-обмена (см. раздел 4)
  created_at timestamptz default now()
);

-- Чаты (в v1 — только приватные 1:1)
create table chats (
  id uuid primary key default gen_random_uuid(),
  is_group boolean default false,
  created_at timestamptz default now()
);

create table chat_participants (
  chat_id uuid references chats(id) on delete cascade,
  user_id uuid references profiles(id) on delete cascade,
  primary key (chat_id, user_id)
);

-- Сообщения: сервер хранит ТОЛЬКО шифротекст
create table messages (
  id uuid primary key default gen_random_uuid(),
  chat_id uuid references chats(id) on delete cascade,
  sender_id uuid references profiles(id),
  ciphertext text not null,
  msg_key text not null,
  created_at timestamptz default now()
);
```

Настрой **Row Level Security**: пользователь видит только свой профиль (на запись) и профили всех (на чтение — для поиска по username, без приватных полей); сообщения/чаты доступны только участникам чата (`chat_participants`).

Включи Realtime-репликацию на таблицу `messages`, чтобы клиент подписывался на новые записи по `chat_id`.

---

## 3. Аутентификация и регистрация по email

1. Экран ввода email.
2. Отправка одноразового кода на почту через Supabase Auth (встроенный email OTP) **или** Edge Function + бесплатный email API (Resend / Brevo free tier — любой на выбор, они взаимозаменяемы).
3. Экран ввода кода → создание сессии.
4. Если это первый вход — переход на экран настройки профиля (обязателен перед входом в приложение).

---

## 4. Шифрование (схема по мотивам MTProto 2.0)

Цель: сервер не может прочитать содержимое сообщений (zero-knowledge). Реализуй двухуровневую защиту, как в оригинальном MTProto — транспорт + полезная нагрузка:

**Уровень 1 — транспорт.** Всё соединение с Supabase идёт по TLS 1.3 (HTTPS/WSS) "из коробки".

**Уровень 2 — шифрование сообщений (payload):**
1. При первом запуске приложение генерирует на устройстве пару ключей (EC25519 или RSA-2048). Приватный ключ **никогда** не покидает устройство и хранится в Android Keystore. Публичный ключ отправляется на сервер и сохраняется в `profiles.public_key`.
2. При первом сообщении между двумя пользователями клиенты выполняют Diffie-Hellman обмен по публичным ключам собеседника и вычисляют общий секрет `auth_key`. Он кэшируется локально в зашифрованном виде (обёрнут ключом из Keystore).
3. Для каждого сообщения (аналогично MTProto 2.0):
   - `msg_key = SHA256(auth_key || plaintext)` (усечённый);
   - `aes_key, aes_iv = KDF(auth_key, msg_key)`;
   - `ciphertext = AES-256-IGE(plaintext, aes_key, aes_iv)`.
4. На сервер уходят только `ciphertext`, `msg_key` и метаданные (`chat_id`, `sender_id`, `created_at`). Расшифровка возможна только на устройствах участников чата, владеющих `auth_key`.

**Локальное хранение:** файл базы данных Room зашифрован через SQLCipher; мастер-ключ SQLCipher хранится в Android Keystore (аппаратный secure enclave при наличии). Дополнительно предусмотри опциональный экран блокировки приложения (PIN/биометрия) перед открытием чатов.

⚠️ Важное замечание для агента: это **не буквальная копия** протокола Telegram (он проприетарный), а собственная реализация "в духе" MTProto 2.0 с использованием проверенных крипто-примитивов (Google Tink / javax.crypto), а не самописных режимов шифрования. Перед продакшеном такая схема требует независимого security-аудита.

---

## 5. Профиль пользователя

Экран настройки профиля (обязателен при первом входе):
- Username (уникальный, для поиска другими пользователями);
- Отображаемое имя;
- Аватар — загрузка фото, хранение в Supabase Storage bucket `avatars` (публичный read, запись — только владелец через RLS/policy), в `profiles.avatar_url` сохраняется ссылка;
- Bio (текстовое описание, до ~150 символов).

---

## 6. UI/UX — дизайн-система

Минимализм, два варианта темы на выбор пользователя:
- **Тёмная:** фон `#0A0E17`, поверхности `#0B1F3A` (глубокий тёмно-синий), акцент `#3D6FFF`, текст `#E7ECF5`.
- **Светлая:** фон `#F5F6F8`, поверхности `#FFFFFF`, текст `#1C1C1E`, акцент — тот же синий `#3D6FFF`.

Шрифт — Inter/Roboto. Пузыри сообщений — плоские, без теней, скруглённые углы, чёткая иерархия (свои/чужие сообщения различаются выравниванием и цветом фона). Никаких лишних декоративных элементов.

**Экраны:**
1. Splash
2. Вход по email → ввод кода
3. Настройка профиля (username, аватар, bio)
4. Список чатов (поиск по username в шапке)
5. Экран чата (лента сообщений + поле ввода текста)
6. Настройки (тема, выход, блокировка приложения)

---

## 7. Функционал v1 (прототип)

- Отправка и получение **только текстовых** сообщений в реальном времени (Supabase Realtime).
- Поиск пользователя по username через поисковую строку → открытие/создание чата (`insert` в `chats` + `chat_participants`).
- История сообщений хранится зашифрованной и на сервере, и локально (см. раздел 4).
- Redактирование профиля (аватар, bio, имя) после регистрации.

**Вне скоупа v1** (зафиксируй как roadmap v2): вложения/медиа, групповые чаты, звонки, push-уведомления (FCM), статусы прочтения, индикатор "печатает", самоуничтожающиеся сообщения.

---

## 8. Порядок реализации (для агента)

1. Прочитать ключи из `.env` в корне проекта (раздел 1.1) и подключиться к Supabase; прогнать SQL-миграции: таблицы, RLS-политики, Storage bucket `avatars`, Realtime-publication на `messages`.
2. Реализовать Auth-флоу (email OTP) на клиенте.
3. Реализовать генерацию ключей и криптомодуль (раздел 4) как отдельный, покрытый тестами слой.
4. Экран настройки профиля + загрузка аватара.
5. Локальная БД (Room + SQLCipher) и синхронизация с Supabase Realtime.
6. Список чатов, поиск по username, экран переписки.
7. Настройки/тема/блокировка приложения.

