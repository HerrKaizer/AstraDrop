package dev.tapdrop

import java.security.SecureRandom

/** Two random words make the share link code, e.g. `pelmeni-babushka`. */
object LinkWords {
    val words: List<String> = listOf(
        // The user's own list
        "vodka", "medved", "borsch", "bublik", "babushka", "samovar", "blin", "tovarishch",
        "ushanka", "pelmeni", "pivo", "smetana", "lomonosov", "chiki-briki", "patsanyi", "samogon",
        "shashlik", "ogurets", "pushkin", "semechki", "krendelyok", "cheremsha", "kvas", "kompot",
        "kasha", "kotleta", "pirozhok", "gopnik", "zhdun", "slonik", "perestroika", "okak",
        "skuf", "chushpan", "plaki-plaki", "doshik",
        // Food and drink
        "vinegret", "olivie", "holodets", "okroshka", "solyanka", "syrniki", "vareniki", "golubtsy",
        "kefir", "ryazhenka", "tvorog", "sushki", "pryanik", "zefir", "sgushchenka", "mors",
        "baranka", "kolbasa", "selyodka", "shuba", "ikra", "grechka", "kartoshka", "pechenye",
        "chebupeli", "beliash", "chak-chak", "kisel", "tarkhun", "buratino", "baikal", "kulebyaka",
        // Things around the house and the yard
        "matryoshka", "balalaika", "valenki", "telnyashka", "kokoshnik", "sarafan", "tulup", "tapki",
        "kovyor", "chaynik", "tazik", "podstakannik", "avoska", "kipyatok", "dacha", "banya",
        "venik", "balkon", "podyezd", "garazh", "khrushchyovka", "marshrutka", "elektrichka", "zhiguli",
        "kopeika", "troika", "gzhel", "khokhloma", "shashki", "domino", "semki", "abibas",
        // Space, science and books
        "sputnik", "gagarin", "kosmonavt", "lunokhod", "belka", "strelka", "mendeleev", "tolstoy",
        "chekhov", "gogol", "kolkhoz", "pyatiletka", "katyusha", "kalinka", "cheburashka", "prostokvashino",
        // Animals and memes
        "yozhik", "mishka", "zaychik", "vorobey", "kotik", "kartuz", "krinzh", "kek",
        "nyasha", "ponyal", "shlyopa", "krosh", "nyusha", "smesharik", "masyanya", "upyachka",
    )

    private val rnd = SecureRandom()

    /** Two different random words joined with a dash. */
    fun pair(): String {
        val a = rnd.nextInt(words.size)
        var b = rnd.nextInt(words.size - 1)
        if (b >= a) b++
        return "${words[a]}-${words[b]}"
    }
}
