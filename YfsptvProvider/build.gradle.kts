version = 3

cloudstream {
    description = "爱壹帆 (yfsp.tv) - Chinese streaming site - Movies, TV, Variety, Anime"
    authors = listOf("minor")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    language = "zh"
    iconUrl = "https://www.google.com/s2/favicons?domain=yfsp.tv&sz=64"
}

dependencies {
    val testImplementation by configurations
    testImplementation("junit:junit:4.13.2")
}
