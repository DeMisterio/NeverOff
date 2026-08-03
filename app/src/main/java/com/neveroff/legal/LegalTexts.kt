package com.neveroff.legal

import com.neveroff.util.AppLanguage

/**
 * The Terms of Use and Privacy Policy shown before an account is created.
 *
 * NOT LEGAL ADVICE, AND NOT YET REVIEWED BY A LAWYER. These are written to
 * describe accurately what this app actually does — end-to-end encryption on
 * private messages, plaintext channel posts, consent-gated analytics,
 * moderation that can only act on what a reporter chooses to share — rather
 * than to be generic boilerplate. That accuracy is the useful part; the legal
 * sufficiency for any particular jurisdiction is not something this file can
 * establish. Have counsel review both documents before they bind anyone.
 *
 * Written as plain data rather than string resources on purpose: the consent
 * screen has to render the text in the language the person picked seconds
 * earlier, before any Activity has been recreated with a new configuration.
 * Going through Android's resource system would show them the previous locale.
 */
object LegalTexts {

    data class Section(val heading: String, val body: String)

    data class Document(
        val title: String,
        val lastUpdated: String,
        val intro: String,
        val sections: List<Section>
    )

    /** Bumped whenever wording changes materially. Stored with the recorded consent. */
    const val VERSION = 1
    private const val LAST_UPDATED_EN = "3 August 2026"
    private const val LAST_UPDATED_RU = "3 августа 2026 г."

    fun terms(language: AppLanguage.Language): Document =
        if (language == AppLanguage.Language.RUSSIAN) termsRu else termsEn

    fun privacy(language: AppLanguage.Language): Document =
        if (language == AppLanguage.Language.RUSSIAN) privacyRu else privacyEn

    // -----------------------------------------------------------------------
    // Terms of Use — English
    // -----------------------------------------------------------------------
    private val termsEn = Document(
        title = "Terms of Use",
        lastUpdated = LAST_UPDATED_EN,
        intro = "These terms are the agreement between you and NeverOff. They are written to be " +
            "read, not to be skipped. If you do not agree with them, you cannot use the app — " +
            "and that is a genuine choice, not a formality.",
        sections = listOf(
            Section(
                "1. Who can use NeverOff",
                "You must be at least 13 years old, and old enough to agree to these terms where " +
                    "you live. If you are under 16 in the European Economic Area or the United " +
                    "Kingdom, a parent or guardian must agree on your behalf.\n\n" +
                    "One person, one account. Do not create an account for someone else or " +
                    "pretend to be someone you are not."
            ),
            Section(
                "2. Your account",
                "You sign in with your email address and a one-time code. Anyone with access to " +
                    "that inbox can sign in as you, so keep it secure.\n\n" +
                    "Your messages are encrypted with a key that exists only on your device. If " +
                    "you lose the device and have no other signed-in device, those messages " +
                    "cannot be recovered — not by you and not by us. This is a consequence of " +
                    "the encryption, not a limitation we can lift on request."
            ),
            Section(
                "3. What you may not do",
                "Do not use NeverOff to:\n\n" +
                    "• harass, threaten, stalk or bully anyone\n" +
                    "• share sexual content involving anyone under 18, ever, in any form\n" +
                    "• share intimate images of someone without their consent\n" +
                    "• attack people for who they are — race, religion, ethnicity, disability, " +
                    "sex, gender identity or sexual orientation\n" +
                    "• threaten, incite or glorify violence\n" +
                    "• send unsolicited bulk messages, run scams, or phish for credentials\n" +
                    "• sell drugs, weapons, stolen data or counterfeit goods\n" +
                    "• impersonate another person, a brand, or NeverOff staff\n" +
                    "• attack, probe or overload our infrastructure\n\n" +
                    "These rules apply to private messages, group chats, channels, your profile " +
                    "and your username alike."
            ),
            Section(
                "4. Channels are public",
                "Anything you post to a channel is stored in readable form on our servers and is " +
                    "visible to everyone who opens that channel. A \"private\" channel is " +
                    "unlisted, not access-controlled: anyone holding the link can read it.\n\n" +
                    "Nothing secret belongs in a channel. Private messages are the encrypted " +
                    "part of this app; channels are the published part."
            ),
            Section(
                "5. How moderation actually works",
                "We cannot read your private messages. So we do not proactively scan them, and " +
                    "we could not if we wanted to.\n\n" +
                    "When someone reports a message, their device may include the decrypted text " +
                    "in the report — but only if they explicitly choose to share it. That " +
                    "voluntary disclosure, together with information we can see anyway (who sent " +
                    "how many messages, to how many people, how quickly) is what a moderator " +
                    "actually reviews.\n\n" +
                    "Channel posts and profiles are readable to us and are moderated directly."
            ),
            Section(
                "6. What happens if you break the rules",
                "Depending on what happened and whether it has happened before, we may: record " +
                    "an internal note, send you a warning, disable specific features such as " +
                    "sending media or links, limit how fast you can send, mute you temporarily, " +
                    "suspend your account for a set period, or permanently suspend it.\n\n" +
                    "You will be told which rule was broken and, for a temporary suspension, " +
                    "exactly when it ends. Access is restored automatically when it does.\n\n" +
                    "Automation may flag content, hide it pending review, or slow an account " +
                    "down. It never permanently suspends anyone on its own — a person decides " +
                    "that."
            ),
            Section(
                "7. Appeals",
                "You can appeal any suspension from the screen that tells you about it. Your " +
                    "appeal is reviewed by someone other than the person who made the original " +
                    "decision. We aim to respond within 72 hours.\n\n" +
                    "If we got it wrong, we lift the restriction. We would rather be told we " +
                    "were wrong than have you leave believing we were."
            ),
            Section(
                "8. Reporting others",
                "Reports are anonymous — the person you report is never told who reported them.\n\n" +
                    "Reporting in bad faith is itself a rule violation. If you repeatedly report " +
                    "content that turns out to be fine, we will tell you, and we may limit how " +
                    "often you can report."
            ),
            Section(
                "9. Updates to the app",
                "NeverOff can download and install its own updates. Each update is checked " +
                    "against a cryptographic checksum before it installs; if that check fails, " +
                    "the update is refused. You can turn automatic downloads off in Settings and " +
                    "check for updates manually instead."
            ),
            Section(
                "10. Ending things",
                "You can delete your account at any time from Settings. Deletion removes your " +
                    "messages, profile and account data — see the Privacy Policy for what is " +
                    "kept and for how long.\n\n" +
                    "We may suspend or close an account that seriously or repeatedly breaks " +
                    "these rules."
            ),
            Section(
                "11. No warranty, and the limits of what we promise",
                "NeverOff is provided as it is. We work to keep it running and secure, but we " +
                    "cannot promise it will never be unavailable, never lose a message, or never " +
                    "contain a bug.\n\n" +
                    "Encryption protects your messages in transit and on our servers. It does " +
                    "not protect you from someone reading your unlocked screen, from malware on " +
                    "your device, or from the person you are talking to sharing what you said."
            ),
            Section(
                "12. Changes to these terms",
                "If we change these terms materially, we will show you the new version and ask " +
                    "you to agree again. We will not quietly change what you agreed to."
            )
        )
    )

    // -----------------------------------------------------------------------
    // Privacy Policy — English
    // -----------------------------------------------------------------------
    private val privacyEn = Document(
        title = "Privacy Policy",
        lastUpdated = LAST_UPDATED_EN,
        intro = "This explains what we collect, what we genuinely cannot see, and what you " +
            "control. Where the honest answer is \"we do not have that\", it says so.",
        sections = listOf(
            Section(
                "1. What we cannot read",
                "Your private and group messages are end-to-end encrypted. The key is derived on " +
                    "your device from a private key that never leaves it. What our servers store " +
                    "is ciphertext we have no way to decrypt.\n\n" +
                    "This means no one at NeverOff can read your private messages. It also means " +
                    "we cannot recover them for you, cannot hand them to anyone else, and cannot " +
                    "produce them in response to a legal request — because we do not have them.\n\n" +
                    "Files and photos you send in chats are encrypted on your device before " +
                    "upload. The same applies."
            ),
            Section(
                "2. What we do hold",
                "• Your email address, used to sign you in\n" +
                    "• Your username, display name, bio and profile picture\n" +
                    "• Who is in which conversation, and when messages were sent and read — the " +
                    "envelope, not the letter\n" +
                    "• Channel posts, in readable form, because channels are public by design\n" +
                    "• A notification token for your device, so we can wake the app\n" +
                    "• The country your account was created from, derived once at registration"
            ),
            Section(
                "3. Location",
                "We do not collect precise location. We derive an approximate country from the " +
                    "network address you connect from, store that country, and discard the " +
                    "address itself after a short security window.\n\n" +
                    "Country is used to route reports to moderators who work in your region and " +
                    "to comply with local law. It is never shown as your location to anyone."
            ),
            Section(
                "4. Analytics, and your choice about it",
                "At registration you choose separately whether to allow analytics and " +
                    "personalisation. Essential data — your account and your messages — cannot " +
                    "be switched off, because the app does not work without it. Everything else " +
                    "can.\n\n" +
                    "If you decline analytics, we do not collect it. Not \"collect it and hide " +
                    "it from reports\" — the system refuses to record it at all.\n\n" +
                    "When you do allow it, we record which features were used, on which screen, " +
                    "on what app version. We never record the content of messages, and any " +
                    "analytics event that appears to contain message text is rejected rather " +
                    "than stored.\n\n" +
                    "You can change your mind at any time in Settings."
            ),
            Section(
                "5. Moderation data",
                "If you report someone, we store the report, the reason you chose, and any " +
                    "content you explicitly chose to share with it.\n\n" +
                    "If someone reports you, we store the report and the outcome. Enforcement " +
                    "actions against your account are kept as a history, because repeat " +
                    "behaviour is relevant context for the next decision.\n\n" +
                    "Reported evidence is kept for the case plus its appeal window, then deleted."
            ),
            Section(
                "6. How long we keep things",
                "• Messages: until you or the other person deletes them, or your account is deleted\n" +
                    "• Analytics events: 90 days, then deleted; aggregate counts are kept longer " +
                    "and contain no personal data\n" +
                    "• Report evidence: around 400 days, covering the case and any appeal\n" +
                    "• Support conversations: 2 years\n" +
                    "• Sign-in sessions: 30 days after they expire\n\n" +
                    "Records under legal hold — subject to a live investigation or legal " +
                    "obligation — are kept until that hold is released."
            ),
            Section(
                "7. Who else sees your data",
                "We do not sell your data. We do not share it for advertising.\n\n" +
                    "We use service providers to run the app: hosting and database, and Google " +
                    "Firebase to deliver push notifications. Notification payloads carry " +
                    "encrypted message content, so the notification service cannot read your " +
                    "messages either.\n\n" +
                    "We disclose data to authorities only where legally required, and only what " +
                    "we actually hold — which, for message content, is nothing readable."
            ),
            Section(
                "8. Your rights",
                "You can ask us to give you a copy of your data, correct it, or delete it. Open " +
                    "@support and choose \"Privacy and your data\". We aim to respond within 30 " +
                    "days.\n\n" +
                    "Deleting your account removes your profile, your messages, your consent " +
                    "records and your analytics history. Aggregate statistics that contain no " +
                    "personal data remain, as do records under legal hold."
            ),
            Section(
                "9. On your device",
                "The copy of your conversations stored on your phone is encrypted with a key held " +
                    "in the device keystore. Signing out deletes that local database and the " +
                    "encryption keys with it."
            ),
            Section(
                "10. Children",
                "NeverOff is not for children under 13. If we learn an account belongs to someone " +
                    "younger, we remove it. Reports of child endangerment are handled through a " +
                    "separate, restricted process and are treated as the highest priority we have."
            ),
            Section(
                "11. Contact",
                "Open @support inside the app. It works even if your account is suspended."
            )
        )
    )

    // -----------------------------------------------------------------------
    // Условия использования — Русский
    // -----------------------------------------------------------------------
    private val termsRu = Document(
        title = "Условия использования",
        lastUpdated = LAST_UPDATED_RU,
        intro = "Это соглашение между вами и NeverOff. Оно написано, чтобы его прочитали, а не " +
            "пролистали. Если вы не согласны, пользоваться приложением нельзя — и это " +
            "настоящий выбор, а не формальность.",
        sections = listOf(
            Section(
                "1. Кто может пользоваться NeverOff",
                "Вам должно быть не менее 13 лет и достаточно лет, чтобы принять эти условия по " +
                    "законам вашей страны. Если вам меньше 16 лет в ЕЭЗ или Великобритании, " +
                    "согласие должен дать родитель или опекун.\n\n" +
                    "Один человек — один аккаунт. Не создавайте аккаунт за другого и не выдавайте " +
                    "себя за кого-то другого."
            ),
            Section(
                "2. Ваш аккаунт",
                "Вход выполняется по адресу электронной почты и одноразовому коду. Любой, у кого " +
                    "есть доступ к этой почте, сможет войти как вы — берегите её.\n\n" +
                    "Ваши сообщения зашифрованы ключом, который существует только на вашем " +
                    "устройстве. Если вы потеряете устройство и у вас нет другого, где вы вошли, " +
                    "эти сообщения восстановить нельзя — ни вам, ни нам. Это следствие " +
                    "шифрования, а не ограничение, которое мы можем снять по просьбе."
            ),
            Section(
                "3. Что запрещено",
                "Нельзя использовать NeverOff, чтобы:\n\n" +
                    "• преследовать, угрожать или травить кого-либо\n" +
                    "• распространять сексуальный контент с участием лиц младше 18 лет — никогда " +
                    "и ни в каком виде\n" +
                    "• распространять интимные изображения человека без его согласия\n" +
                    "• нападать на людей за то, кто они есть: расу, религию, этническую " +
                    "принадлежность, инвалидность, пол, гендерную идентичность или ориентацию\n" +
                    "• угрожать насилием, призывать к нему или прославлять его\n" +
                    "• рассылать массовый спам, мошенничать или выманивать учётные данные\n" +
                    "• продавать наркотики, оружие, краденые данные или контрафакт\n" +
                    "• выдавать себя за другого человека, бренд или сотрудников NeverOff\n" +
                    "• атаковать или перегружать нашу инфраструктуру\n\n" +
                    "Эти правила одинаково действуют в личных сообщениях, группах, каналах, " +
                    "профиле и имени пользователя."
            ),
            Section(
                "4. Каналы публичны",
                "Всё, что вы публикуете в канале, хранится на наших серверах в читаемом виде и " +
                    "видно каждому, кто откроет канал. «Приватный» канал — это канал, скрытый из " +
                    "поиска, а не закрытый: любой, у кого есть ссылка, сможет его прочитать.\n\n" +
                    "Секретам не место в канале. Личные сообщения — это зашифрованная часть " +
                    "приложения, каналы — опубликованная."
            ),
            Section(
                "5. Как на самом деле работает модерация",
                "Мы не можем читать ваши личные сообщения. Поэтому мы их не сканируем — и не " +
                    "смогли бы, даже если бы захотели.\n\n" +
                    "Когда кто-то отправляет жалобу, его устройство может приложить расшифрованный " +
                    "текст — но только если человек явно согласится им поделиться. Именно это " +
                    "добровольное раскрытие вместе с данными, которые мы видим и так (кто сколько " +
                    "сообщений отправил, скольким людям и как быстро), и рассматривает модератор.\n\n" +
                    "Публикации в каналах и профили нам доступны и модерируются напрямую."
            ),
            Section(
                "6. Что будет при нарушении правил",
                "В зависимости от того, что произошло и было ли это раньше, мы можем: сделать " +
                    "внутреннюю пометку, направить предупреждение, отключить отдельные функции " +
                    "(например, отправку медиа или ссылок), ограничить скорость отправки, " +
                    "временно лишить возможности писать, приостановить аккаунт на срок или " +
                    "заблокировать его навсегда.\n\n" +
                    "Вам сообщат, какое правило нарушено, а при временной блокировке — точную " +
                    "дату и время её окончания. Доступ восстанавливается автоматически.\n\n" +
                    "Автоматика может пометить контент, скрыть его до проверки или замедлить " +
                    "аккаунт. Она никогда не блокирует навсегда сама — это решает человек."
            ),
            Section(
                "7. Обжалование",
                "Любую блокировку можно обжаловать прямо на экране, который о ней сообщает. " +
                    "Жалобу рассматривает не тот человек, который принял исходное решение. Мы " +
                    "стремимся ответить в течение 72 часов.\n\n" +
                    "Если мы ошиблись — мы снимем ограничение. Нам лучше услышать, что мы были " +
                    "неправы, чем чтобы вы ушли, считая нас неправыми."
            ),
            Section(
                "8. Жалобы на других",
                "Жалобы анонимны — человеку никогда не сообщают, кто на него пожаловался.\n\n" +
                    "Недобросовестные жалобы сами по себе являются нарушением. Если вы регулярно " +
                    "жалуетесь на контент, с которым всё в порядке, мы вам об этом скажем и " +
                    "можем ограничить частоту жалоб."
            ),
            Section(
                "9. Обновления приложения",
                "NeverOff может загружать и устанавливать собственные обновления. Каждое " +
                    "обновление проверяется по криптографической контрольной сумме; если " +
                    "проверка не проходит, установка отклоняется. Автозагрузку можно отключить в " +
                    "настройках и проверять обновления вручную."
            ),
            Section(
                "10. Прекращение использования",
                "Вы можете удалить аккаунт в любой момент в настройках. Удаление стирает ваши " +
                    "сообщения, профиль и данные аккаунта — что именно сохраняется и на какой " +
                    "срок, описано в Политике конфиденциальности.\n\n" +
                    "Мы можем приостановить или закрыть аккаунт при серьёзных или повторных " +
                    "нарушениях."
            ),
            Section(
                "11. Отсутствие гарантий",
                "NeverOff предоставляется как есть. Мы работаем над тем, чтобы он был доступен и " +
                    "безопасен, но не можем обещать, что он никогда не станет недоступен, никогда " +
                    "не потеряет сообщение и никогда не будет содержать ошибок.\n\n" +
                    "Шифрование защищает сообщения при передаче и на наших серверах. Оно не " +
                    "защитит от того, кто смотрит в ваш разблокированный экран, от вредоносных " +
                    "программ на устройстве и от собеседника, который перескажет ваши слова."
            ),
            Section(
                "12. Изменения условий",
                "Если мы существенно изменим эти условия, мы покажем новую версию и попросим " +
                    "согласиться заново. Мы не станем незаметно менять то, с чем вы согласились."
            )
        )
    )

    // -----------------------------------------------------------------------
    // Политика конфиденциальности — Русский
    // -----------------------------------------------------------------------
    private val privacyRu = Document(
        title = "Политика конфиденциальности",
        lastUpdated = LAST_UPDATED_RU,
        intro = "Здесь описано, что мы собираем, чего мы действительно не видим и чем управляете " +
            "вы. Там, где честный ответ — «у нас этого нет», так и написано.",
        sections = listOf(
            Section(
                "1. Что мы не можем прочитать",
                "Ваши личные и групповые сообщения защищены сквозным шифрованием. Ключ " +
                    "вычисляется на вашем устройстве из закрытого ключа, который его никогда не " +
                    "покидает. На наших серверах хранится шифротекст, который мы не можем " +
                    "расшифровать.\n\n" +
                    "Это значит, что никто в NeverOff не может прочитать ваши личные сообщения. " +
                    "Это же значит, что мы не можем их вам восстановить, не можем передать их " +
                    "кому-либо и не можем предоставить их по законному запросу — потому что их у " +
                    "нас нет.\n\n" +
                    "Файлы и фотографии в чатах шифруются на устройстве до загрузки. К ним " +
                    "относится всё то же самое."
            ),
            Section(
                "2. Что у нас есть",
                "• Адрес электронной почты — для входа\n" +
                    "• Имя пользователя, отображаемое имя, описание и фото профиля\n" +
                    "• Кто в каком разговоре участвует, когда сообщения отправлены и прочитаны — " +
                    "конверт, но не письмо\n" +
                    "• Публикации в каналах в читаемом виде, поскольку каналы публичны по замыслу\n" +
                    "• Токен уведомлений вашего устройства, чтобы разбудить приложение\n" +
                    "• Страна, из которой создан аккаунт, определённая один раз при регистрации"
            ),
            Section(
                "3. Местоположение",
                "Мы не собираем точное местоположение. Мы один раз определяем приблизительную " +
                    "страну по сетевому адресу, сохраняем страну, а сам адрес удаляем после " +
                    "короткого периода, нужного для безопасности.\n\n" +
                    "Страна используется, чтобы направлять жалобы модераторам вашего региона и " +
                    "соблюдать местное законодательство. Она никогда не показывается другим как " +
                    "ваше местоположение."
            ),
            Section(
                "4. Аналитика и ваш выбор",
                "При регистрации вы отдельно решаете, разрешать ли аналитику и персонализацию. " +
                    "Необходимые данные — ваш аккаунт и ваши сообщения — отключить нельзя, без " +
                    "них приложение не работает. Всё остальное отключается.\n\n" +
                    "Если вы откажетесь от аналитики, мы её не собираем. Не «соберём и скроем из " +
                    "отчётов» — система просто откажется её записывать.\n\n" +
                    "Если вы согласитесь, мы фиксируем, какие функции использовались, на каком " +
                    "экране и в какой версии приложения. Мы никогда не записываем содержание " +
                    "сообщений, а событие, которое похоже на текст сообщения, отклоняется, а не " +
                    "сохраняется.\n\n" +
                    "Решение можно изменить в любой момент в настройках."
            ),
            Section(
                "5. Данные модерации",
                "Если вы жалуетесь на кого-то, мы сохраняем жалобу, выбранную причину и тот " +
                    "контент, которым вы явно решили поделиться.\n\n" +
                    "Если жалуются на вас, мы сохраняем жалобу и результат. История мер по " +
                    "вашему аккаунту сохраняется, потому что повторяющееся поведение важно для " +
                    "следующего решения.\n\n" +
                    "Приложенные доказательства хранятся на время рассмотрения и обжалования, " +
                    "после чего удаляются."
            ),
            Section(
                "6. Сроки хранения",
                "• Сообщения — пока вы или собеседник их не удалите либо пока не удалён аккаунт\n" +
                    "• События аналитики — 90 дней; агрегированные счётчики хранятся дольше и не " +
                    "содержат персональных данных\n" +
                    "• Доказательства по жалобам — около 400 дней\n" +
                    "• Переписка с поддержкой — 2 года\n" +
                    "• Сессии входа — 30 дней после истечения\n\n" +
                    "Записи, на которые наложен юридический запрет на удаление, хранятся до его " +
                    "снятия."
            ),
            Section(
                "7. Кто ещё видит ваши данные",
                "Мы не продаём ваши данные и не передаём их для рекламы.\n\n" +
                    "Мы пользуемся сервис-провайдерами: хостингом и базой данных, а также Google " +
                    "Firebase для доставки push-уведомлений. В уведомлениях передаётся " +
                    "зашифрованное содержимое, поэтому служба уведомлений тоже не может прочитать " +
                    "ваши сообщения.\n\n" +
                    "Мы раскрываем данные органам власти только когда это требуется по закону, и " +
                    "только то, что у нас действительно есть — а содержимого сообщений в " +
                    "читаемом виде у нас нет."
            ),
            Section(
                "8. Ваши права",
                "Вы можете запросить копию своих данных, их исправление или удаление. Откройте " +
                    "@support и выберите «Конфиденциальность и ваши данные». Мы стремимся " +
                    "ответить в течение 30 дней.\n\n" +
                    "Удаление аккаунта стирает профиль, сообщения, записи о согласии и историю " +
                    "аналитики. Остаются агрегированные показатели без персональных данных и " +
                    "записи под юридическим запретом на удаление."
            ),
            Section(
                "9. На вашем устройстве",
                "Копия переписки на телефоне зашифрована ключом из хранилища ключей устройства. " +
                    "Выход из аккаунта удаляет эту локальную базу вместе с ключами."
            ),
            Section(
                "10. Дети",
                "NeverOff не предназначен для детей младше 13 лет. Если мы узнаем, что аккаунт " +
                    "принадлежит человеку младше, мы его удалим. Сообщения об угрозе " +
                    "несовершеннолетним обрабатываются по отдельной ограниченной процедуре и " +
                    "имеют наивысший приоритет."
            ),
            Section(
                "11. Связь с нами",
                "Откройте @support в приложении. Он работает даже если ваш аккаунт заблокирован."
            )
        )
    )
}
