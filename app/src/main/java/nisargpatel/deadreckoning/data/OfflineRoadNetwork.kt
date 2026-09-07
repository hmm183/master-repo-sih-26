package nisargpatel.deadreckoning.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer
import org.openstreetmap.osmosis.core.domain.v0_6.EntityType
import org.openstreetmap.osmosis.core.domain.v0_6.Node
import org.openstreetmap.osmosis.core.domain.v0_6.Relation
import org.openstreetmap.osmosis.core.domain.v0_6.Way
import org.openstreetmap.osmosis.core.task.v0_6.Sink
import org.openstreetmap.osmosis.pbf2.v0_6.PbfReader
import org.osmdroid.util.GeoPoint
import java.io.File
import java.util.PriorityQueue
import kotlin.math.cos
import kotlin.math.roundToInt

data class RoadSegment(
    val wayId: Long,
    val name: String,
    val points: List<GeoPoint>,
    val nodeIds: List<Long>,
    val oneWay: Boolean = false,
    val maxSpeedKph: Int? = null,
    val access: String? = null,
    val highway: String = "road",
    val roundabout: Boolean = false,
    val lanes: Int? = null
)

data class OfflineMapPackage(val id: String, val displayName: String, val fileName: String, val bytes: Long, val importedAtMillis: Long, val segmentCount: Int)
data class OfflineRoadNetworkState(
    val segmentCount: Int = 0,
    val isDownloading: Boolean = false,
    val message: String = "No regional road package imported",
    val packages: List<OfflineMapPackage> = emptyList(),
    val restrictionCount: Int = 0
)

private data class TurnRestriction(val fromWay: Long, val toWay: Long, val viaNode: Long, val only: Boolean)
private data class StoredNetwork(val segments: List<StoredSegment>, val restrictions: List<TurnRestriction>, val packages: List<OfflineMapPackage>)
private data class StoredSegment(val wayId: Long, val name: String, val points: List<StoredPoint>, val nodeIds: List<Long>, val oneWay: Boolean, val maxSpeedKph: Int?, val access: String?, val highway: String, val roundabout: Boolean, val lanes: Int?)
private data class StoredPoint(val latitude: Double, val longitude: Double)

/** Persistent regional OSM PBF graph with package metadata, travel metadata, and turn restrictions. */
class OfflineRoadNetwork private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: OfflineRoadNetwork? = null
        fun get(context: Context): OfflineRoadNetwork = instance ?: synchronized(this) {
            instance ?: OfflineRoadNetwork(context.applicationContext).also { instance = it }
        }
    }

    private val gson = Gson()
    private val networkFile = File(context.filesDir, "offline_road_network_v2.json")
    private val packageDir = File(context.filesDir, "offline-road-packages").apply { mkdirs() }
    private var restrictions = emptyList<TurnRestriction>()
    private var packages = emptyList<OfflineMapPackage>()
    private var segments = load()
    private var spatialIndex = buildSpatialIndex(segments)
    private var wayAdjacency = buildWayAdjacency(segments)
    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<OfflineRoadNetworkState> = _state.asStateFlow()

    suspend fun importPbf(uri: Uri, displayName: String): Result<Int> = withContext(Dispatchers.IO) {
        _state.value = snapshot(true, "Copying regional OSM package")
        runCatching {
            val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "regional-map.osm.pbf" }
            val target = File(packageDir, "${System.currentTimeMillis()}_$safeName")
            context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use(input::copyTo) }
                ?: error("Unable to open selected OSM PBF package")
            importPbfFile(target, displayName)
        }.onFailure { _state.value = snapshot(false, it.message ?: "Regional package import failed") }
    }

    suspend fun importPbfFile(file: File, displayName: String = file.name): Int = withContext(Dispatchers.IO) {
        _state.value = snapshot(true, "Indexing $displayName")
        val parsed = PbfGraphParser().parse(file)
        require(parsed.segments.isNotEmpty()) { "No routable roads found in this package" }
        segments = (segments + parsed.segments).distinctBy { it.wayId }
        restrictions = (restrictions + parsed.restrictions).distinct()
        spatialIndex = buildSpatialIndex(segments)
        wayAdjacency = buildWayAdjacency(segments)
        packages = (packages.filterNot { it.fileName == file.name } + OfflineMapPackage(file.nameWithoutExtension, displayName, file.name, file.length(), System.currentTimeMillis(), parsed.segments.size)).takeLast(8)
        persist()
        _state.value = snapshot(false, "${segments.size} roads indexed from $displayName")
        segments.size
    }

    fun match(point: GeoPoint): List<RoadCandidate> = nearbySegments(point).mapNotNull { segment ->
        nearestOnSegment(point, segment)?.let { (projected, distance, bearing) ->
            RoadCandidate(segment.name, projected, distance, segment.wayId, bearing, segment.oneWay)
        }
    }.sortedBy { it.distanceMeters }.take(8)

    fun route(start: GeoPoint, end: GeoPoint): List<GeoPoint>? {
        if (segments.isEmpty()) return null
        val graph = HashMap<Long, MutableList<Edge>>()
        val nodes = HashMap<Long, GeoPoint>()
        segments.forEach { segment ->
            segment.points.zip(segment.nodeIds).forEach { (point, id) -> nodes[id] = point }
            segment.nodeIds.zipWithNext().forEach { (from, to) ->
                val distance = nodes[from]!!.distanceToAsDouble(nodes[to]!!)
                val seconds = distance / ((segment.maxSpeedKph ?: defaultSpeed(segment.highway)) / 3.6)
                graph.getOrPut(from) { mutableListOf() }.add(Edge(to, segment.wayId, seconds))
                if (!segment.oneWay) graph.getOrPut(to) { mutableListOf() }.add(Edge(from, segment.wayId, seconds))
            }
        }
        val startId = nodes.minByOrNull { it.value.distanceToAsDouble(start) }?.key ?: return null
        val endId = nodes.minByOrNull { it.value.distanceToAsDouble(end) }?.key ?: return null
        data class Key(val nodeId: Long, val incomingWay: Long?)
        data class QueueNode(val key: Key, val cost: Double)
        val queue = PriorityQueue<QueueNode>(compareBy { it.cost })
        val startKey = Key(startId, null)
        val costs = hashMapOf(startKey to 0.0)
        val previous = HashMap<Key, Key>()
        queue += QueueNode(startKey, 0.0)
        var destination: Key? = null
        while (queue.isNotEmpty()) {
            val current = queue.remove()
            if (current.cost != costs[current.key]) continue
            if (current.key.nodeId == endId) { destination = current.key; break }
            graph[current.key.nodeId].orEmpty().forEach { edge ->
                if (violatesRestriction(current.key.incomingWay, edge.wayId, current.key.nodeId)) return@forEach
                val next = Key(edge.toNode, edge.wayId)
                val nextCost = current.cost + edge.travelSeconds
                if (nextCost < (costs[next] ?: Double.MAX_VALUE)) {
                    costs[next] = nextCost
                    previous[next] = current.key
                    queue += QueueNode(next, nextCost)
                }
            }
        }
        val endKey = destination ?: return null
        val ids = generateSequence(endKey) { previous[it] }.toList().asReversed().map { it.nodeId }
        return listOf(start) + ids.mapNotNull(nodes::get) + end
    }

    fun clear() {
        networkFile.delete()
        packageDir.listFiles()?.forEach(File::delete)
        segments = emptyList(); restrictions = emptyList(); packages = emptyList(); spatialIndex = emptyMap(); wayAdjacency = emptyMap()
        _state.value = snapshot(false, "Regional road packages cleared")
    }

    private fun violatesRestriction(fromWay: Long?, toWay: Long, viaNode: Long): Boolean {
        if (fromWay == null) return false
        val local = restrictions.filter { it.fromWay == fromWay && it.viaNode == viaNode }
        return local.any { (!it.only && it.toWay == toWay) || (it.only && it.toWay != toWay) }
    }

    private fun snapshot(isBusy: Boolean = false, message: String = if (segments.isEmpty()) "No regional road package imported" else "${segments.size} regional roads ready (Demo package)") =
        OfflineRoadNetworkState(segments.size, isBusy, message, packages, restrictions.size)

    private fun load(): List<RoadSegment> = runCatching {
        val jsonText = if (networkFile.exists()) {
            networkFile.readText()
        } else {
            // Out-of-the-box demo asset fallback
            runCatching {
                context.assets.open("roads/default_regional_network.json").bufferedReader().use { it.readText() }
            }.getOrNull() ?: return@runCatching emptyList()
        }
        val stored = gson.fromJson<StoredNetwork>(jsonText, object : TypeToken<StoredNetwork>() {}.type)
        restrictions = stored.restrictions; packages = stored.packages
        stored.segments.map { item -> RoadSegment(item.wayId, item.name, item.points.map { GeoPoint(it.latitude, it.longitude) }, item.nodeIds, item.oneWay, item.maxSpeedKph, item.access, item.highway, item.roundabout, item.lanes) }
    }.getOrDefault(emptyList())

    private fun persist() {
        val stored = StoredNetwork(segments.map { s -> StoredSegment(s.wayId, s.name, s.points.map { StoredPoint(it.latitude, it.longitude) }, s.nodeIds, s.oneWay, s.maxSpeedKph, s.access, s.highway, s.roundabout, s.lanes) }, restrictions, packages)
        networkFile.writeText(gson.toJson(stored))
    }

    private fun buildSpatialIndex(source: List<RoadSegment>): Map<String, List<RoadSegment>> = source.flatMap { segment ->
        segment.points.map { point -> cellId(point) to segment }
    }.groupBy({ it.first }, { it.second }).mapValues { (_, items) -> items.distinctBy { it.wayId } }

    private fun buildWayAdjacency(source: List<RoadSegment>): Map<Long, Set<Long>> {
        val nodeToWays = HashMap<Long, MutableSet<Long>>()
        source.forEach { seg ->
            seg.nodeIds.forEach { nodeId ->
                nodeToWays.getOrPut(nodeId) { mutableSetOf() }.add(seg.wayId)
            }
        }
        val adjacency = HashMap<Long, MutableSet<Long>>()
        source.forEach { seg ->
            val neighbors = adjacency.getOrPut(seg.wayId) { mutableSetOf() }
            seg.nodeIds.forEach { nodeId ->
                nodeToWays[nodeId]?.let { sharingWays ->
                    neighbors.addAll(sharingWays)
                }
            }
        }
        return adjacency
    }

    /**
     * Determines topological graph distance (in hops) between two OSM ways:
     *  0: Same way
     *  1: Directly adjacent (share at least one intersection node)
     *  2: 2-hop connected (share an intermediate connected neighbor way)
     * -1: Disconnected or distance > 2 hops (e.g. parallel road, elevated flyover, opposite riverbank)
     */
    fun getTopologicalHops(wayA: Long, wayB: Long): Int {
        if (wayA == 0L || wayB == 0L) return 0 // synthetic or unassigned, neutral
        if (wayA == wayB) return 0
        val neighborsA = wayAdjacency[wayA] ?: return -1
        if (neighborsA.contains(wayB)) return 1
        val neighborsB = wayAdjacency[wayB] ?: return -1
        for (neighbor in neighborsA) {
            if (neighborsB.contains(neighbor)) return 2
        }
        return -1
    }

    fun areWaysConnected(wayA: Long, wayB: Long): Boolean = getTopologicalHops(wayA, wayB) >= 0

    private fun nearbySegments(point: GeoPoint): List<RoadSegment> {
        val latitudeCell = (point.latitude * 100).toInt()
        val longitudeCell = (point.longitude * 100).toInt()
        val local = buildList {
            for (lat in latitudeCell - 1..latitudeCell + 1) for (lon in longitudeCell - 1..longitudeCell + 1) addAll(spatialIndex["$lat:$lon"].orEmpty())
        }.distinctBy { it.wayId }
        return local.ifEmpty { segments }
    }

    private fun cellId(point: GeoPoint): String = "${(point.latitude * 100).toInt()}:${(point.longitude * 100).toInt()}"

    private fun nearestOnSegment(point: GeoPoint, segment: RoadSegment): Triple<GeoPoint, Double, Double>? {
        var nearest: GeoPoint? = null; var distance = Double.MAX_VALUE; var bearing = 0.0
        segment.points.zipWithNext().forEach { (start, end) ->
            val candidate = project(point, start, end); val candidateDistance = point.distanceToAsDouble(candidate)
            if (candidateDistance < distance) { nearest = candidate; distance = candidateDistance; bearing = start.bearingTo(end).toDouble() }
        }
        return nearest?.let { Triple(it, distance, bearing) }
    }

    private fun project(point: GeoPoint, start: GeoPoint, end: GeoPoint): GeoPoint {
        val latScale = 111_111.0; val lonScale = latScale * cos(Math.toRadians(point.latitude))
        val dx = (end.longitude - start.longitude) * lonScale; val dy = (end.latitude - start.latitude) * latScale
        val px = (point.longitude - start.longitude) * lonScale; val py = (point.latitude - start.latitude) * latScale
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return start
        val factor = ((px * dx + py * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return GeoPoint(start.latitude + dy * factor / latScale, start.longitude + dx * factor / lonScale)
    }

    private fun defaultSpeed(highway: String) = when (highway) { "motorway", "trunk" -> 80; "primary" -> 60; "secondary" -> 45; else -> 30 }
    private data class Edge(val toNode: Long, val wayId: Long, val travelSeconds: Double)
}

private class PbfGraphParser {
    data class Result(val segments: List<RoadSegment>, val restrictions: List<TurnRestriction>)
    private val nodes = HashMap<Long, GeoPoint>()
    private val ways = HashMap<Long, ParsedWay>()
    private val restrictions = mutableListOf<TurnRestriction>()

    fun parse(file: File): Result {
        val reader = PbfReader(file, 1)
        reader.setSink(object : Sink {
            override fun initialize(metaData: MutableMap<String, Any>) = Unit
            override fun process(entityContainer: EntityContainer) {
                when (val entity = entityContainer.entity) {
                    is Node -> nodes[entity.id] = GeoPoint(entity.latitude, entity.longitude)
                    is Way -> parseWay(entity)?.let { ways[entity.id] = it }
                    is Relation -> parseRestriction(entity)?.let(restrictions::add)
                }
            }
            override fun complete() = Unit
            override fun close() = Unit
        })
        reader.run()
        val segments = ways.values.mapNotNull { way ->
            val paired = way.nodeIds.mapNotNull { id -> nodes[id]?.let { id to it } }
            if (paired.size < 2) null else RoadSegment(way.id, way.name, paired.map { it.second }, paired.map { it.first }, way.oneWay, way.maxSpeedKph, way.access, way.highway, way.roundabout, way.lanes)
        }
        return Result(segments, restrictions)
    }

    private fun parseWay(way: Way): ParsedWay? {
        val tags = way.tags.associate { it.key to it.value }
        val highway = tags["highway"] ?: return null
        if (tags["access"] in setOf("no", "private") || highway in setOf("footway", "path", "cycleway", "steps")) return null
        return ParsedWay(way.id, tags["name"] ?: highway.replaceFirstChar(Char::uppercase), way.wayNodes.map { it.nodeId }, tags["oneway"] in setOf("yes", "1", "true") || tags["junction"] == "roundabout", parseSpeed(tags["maxspeed"]), tags["access"], highway, tags["junction"] == "roundabout", tags["lanes"]?.toIntOrNull())
    }

    private fun parseRestriction(relation: Relation): TurnRestriction? {
        val tags = relation.tags.associate { it.key to it.value }
        val type = tags["restriction"] ?: return null
        val from = relation.members.firstOrNull { it.memberRole == "from" && it.memberType == EntityType.Way } ?: return null
        val to = relation.members.firstOrNull { it.memberRole == "to" && it.memberType == EntityType.Way } ?: return null
        val via = relation.members.firstOrNull { it.memberRole == "via" && it.memberType == EntityType.Node } ?: return null
        return TurnRestriction(from.memberId, to.memberId, via.memberId, type.startsWith("only_"))
    }

    private fun parseSpeed(value: String?): Int? = value?.substringBefore(' ')?.toDoubleOrNull()?.roundToInt()
    private data class ParsedWay(val id: Long, val name: String, val nodeIds: List<Long>, val oneWay: Boolean, val maxSpeedKph: Int?, val access: String?, val highway: String, val roundabout: Boolean, val lanes: Int?)
}

data class RoadCandidate(
    val roadName: String,
    val point: GeoPoint,
    val distanceMeters: Double,
    val wayId: Long = 0L,
    val bearingDegrees: Double = 0.0,
    val oneWay: Boolean = false
)
