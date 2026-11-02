package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.Location
import nl.wdhertog.library.data.db.LocationType

/** Een locatie in de boom, met pad en boekentellingen. */
data class LocationNode(
    val location: Location,
    val depth: Int,
    /** Namen van de wortel tot en met deze locatie. */
    val path: List<String>,
    /** Boeken direct op deze locatie. */
    val directCount: Int,
    /** Boeken op deze locatie en alle sublocaties. */
    val totalCount: Int,
    val children: List<LocationNode>,
) {
    val id: Long get() = location.id
    val pathLabel: String get() = path.joinToString(" › ")
}

object LocationTree {

    fun build(locations: List<Location>, directCounts: Map<Long, Int>): List<LocationNode> {
        val ids = locations.map { it.id }.toSet()
        // Een verwijzing naar een onbekende ouder maakt de locatie een wortel.
        val byParent = locations.groupBy { loc -> loc.parentId?.takeIf { it in ids } }

        fun node(location: Location, depth: Int, parentPath: List<String>, visited: Set<Long>): LocationNode {
            val path = parentPath + location.name
            val children = byParent[location.id].orEmpty()
                .filter { it.id !in visited } // bescherming tegen een kring in de ouderketen
                .map { node(it, depth + 1, path, visited + it.id) }
            val direct = directCounts[location.id] ?: 0
            return LocationNode(location, depth, path, direct, direct + children.sumOf { it.totalCount }, children)
        }

        return byParent[null].orEmpty().map { node(it, 0, emptyList(), setOf(it.id)) }
    }

    /** Diepte-eerst; alleen kinderen van uitgeklapte knopen, of alles als [expanded] null is. */
    fun flatten(roots: List<LocationNode>, expanded: Set<Long>? = null): List<LocationNode> =
        roots.flatMap { node ->
            val showChildren = expanded == null || node.id in expanded
            listOf(node) + if (showChildren) flatten(node.children, expanded) else emptyList()
        }

    fun index(roots: List<LocationNode>): Map<Long, LocationNode> = flatten(roots).associateBy { it.id }

    /** Deze locatie en alles eronder, voor filteren op bijv. een hele kamer. */
    fun descendantIds(node: LocationNode): Set<Long> = flatten(listOf(node)).map { it.id }.toSet()

    /** Logische volgende laag: in een kamer staan kasten, in een kast planken. */
    fun defaultChildType(parent: LocationType?): LocationType = when (parent) {
        null -> LocationType.ROOM
        LocationType.ROOM -> LocationType.BOOKCASE
        LocationType.BOOKCASE -> LocationType.SHELF
        LocationType.SHELF, LocationType.BOX, LocationType.OTHER -> LocationType.OTHER
    }
}
