package io.github.p1neapplexpress.openflux.util

object RussianAppsPreset {

    /**
     * Популярные российские сервисы и приложения (банки, Госуслуги, маркетплейсы,
     * фастфуд, сервисы Яндекса, доставка, связь, такси, образование, умный дом и медиа),
     * чувствительные к зарубежным IP и рекомендуемые для режима обхода по умолчанию.
     */
    val PACKAGE_NAMES: Set<String> = setOf(
        // Банки и финансы
        "ru.sberbankmobile",
        "ru.sberbank.sberkids",
        "com.idamob.tinkoff.android",
        "com.tcsbank.tcsinvest",
        "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android",
        "ru.gazprombank.android.mobilebank.app",
        "ru.raiffeisennews",
        "ru.sovcomcard.halva.v1",
        "ru.mts.money",
        "ru.payment",
        "ru.openbank.mobile.android",
        "ru.bspb",
        "ru.mkb.mobile",
        "ru.rshb.dbo",
        "ru.nspk.mirpay",
        "ru.nspk.sbpay",
        "com.bcr.ubrr",
        "ru.unicredit.unicreditrustablet",

        // Госуслуги и государственные сервисы
        "ru.gosuslugi.esk",
        "ru.gosuslugi.auto",
        "ru.gosuslugi.culture",
        "ru.fns.billchecker",
        "com.tax.mobile",
        "ru.nalog.fl",
        "ru.mos.app",
        "emias.info",
        "ru.russianpost.android",
        "ru.fssprus.fssprushandheld",
        "ru.pfr.mobile",

        // Транспорт, карты, такси, каршеринг
        "ru.yandex.taxi",
        "ru.yandex.yandexmaps",
        "ru.yandex.yandexnavi",
        "ru.dublgis.dgismobile",
        "ru.rzd.pass",
        "ru.delimobil.carsharing",
        "ru.citydrive.app",
        "ru.belkacar.belkacar",
        "ru.aeroflot.mobile",
        "ru.s7.touch",
        "ru.pobeda.aero",
        "com.taxsee.taxsee", // Такси Максим / MAX

        // Маркетплейсы, ритейл, фастфуд и доставка
        "ru.ozon.app.android",
        "com.wildberries.ru",
        "com.avito.android",
        "ru.yandex.market",
        "ru.sbermegamarket.app",
        "ru.yandex.eda",
        "ru.yandex.lavka",
        "ru.sbermarket",
        "ru.sbermarket.android",
        "ru.samokat.app",
        "ru.tander.magnit",
        "ru.x5.retail.loyalty", // X5 Клуб / Пятёрочка
        "ru.pyaterochka.app.browser", // Пятёрочка
        "ru.pyaterochka",
        "ru.vkusvill.app",
        "ru.perekrestok.app",
        "ru.dns.shop", // DNS
        "ru.dns.shop.android", // DNS
        "ru.burgerking", // Burger King
        "com.tapston.burgerking",
        "ru.rostics.app", // Rostic's
        "ru.rostics",
        "ru.kfc.kfc_delivery",
        "com.yum.kfc",
        "ru.dodopizza.app", // Додо Пицца
        "com.dodopizza.driveapp",
        "ru.mvideo.app",
        "ru.eldorado.app",
        "ru.leroymerlin.mobile",
        "ru.maxiapp.client", // Макси / MAX
        "ru.maxi.retail",

        // Связь и телеком
        "ru.mts.mymts",
        "ru.megafon.mlk",
        "ru.beeline.services",
        "ru.tele2.mytele2",
        "ru.rt.myrt",
        "com.yota.user",
        "ru.filit.motiv.app", // МОТИВ
        "ru.motiv.app",
        "ru.ycc.motiv",
        "simmotiv.id.abonent",

        // Умный дом и IoT
        "com.yandex.iot", // Умный дом (Дом с Алисой)
        "ru.rt.smarthome", // Умный дом Ростелеком
        "ru.sberbank.iot", // Умный дом Сбер
        "com.ertelecom.smarthome", // Умный дом Дом.ru
        "ru.mts.smarthome", // Умный дом МТС

        // Образование
        "net.umschool.umschool_flutter.prod", // УМСКУЛ
        "net.umschool.umschool",
        "ru.umschool",
        "ru.umschool.app",
        "ru.maximumtest.app", // MAXIMUM Education / MAX

        // MAX сервисы, утилиты и стриминг
        "com.wbd.stream", // Max
        "ru.makc.app", // МАКС
        "ru.makc.mobile",
        "ru.makc.client",
        "com.nusp.max",
        "com.algorithmservice.max2",

        // Медиа, соцсети и стриминг
        "com.vkontakte.android",
        "ru.ok.android",
        "com.vk.vkvideo",
        "com.vk.music",
        "ru.rutube.app",
        "ru.kinopoisk",
        "ru.yandex.music",
        "ru.yandex.searchplugin",
        "ru.yandex.disk",
        "ru.yandex.mail",
        "ru.mail.mailapp",
        "com.my.cloud",
        "ru.yandex.zen",
        "tv.start.android",
        "ru.premier",
        "ru.kion.kion",
        "ru.smotrim.app",
        "ru.ntv.client"
    )
}
