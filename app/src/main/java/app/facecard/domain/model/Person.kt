package app.facecard.domain.model

/**
 * One continuous clearly-visible segment for a single person
 * (PRD counting contract).
 */
data class Appearance(
    val startMs: Long,
    val endMs: Long,
    val frames: Int,
)

data class Person(
    val id: Int,
    val label: String,
    val samples: List<FaceSample>,
    val appearances: List<Appearance>,
) {
    val appearanceCount: Int get() = appearances.size
}

data class ProcessResult(val people: List<Person>) {
    val personCount: Int get() = people.size
    val totalAppearances: Int get() = people.sumOf { it.appearanceCount }
}
