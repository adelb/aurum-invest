package com.aurum.invest.analytics

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cache serialization for [AllocationPlan].
 *
 * Every read fails CLOSED: a field that is missing or malformed produces null
 * for the whole plan rather than a plan with quietly zeroed dollars. A cached
 * allocation is money advice — half of one is worse than none.
 */
object AllocationPlanJson {

    fun toJson(plan: AllocationPlan): JSONObject = JSONObject().apply {
        put("computedAt", plan.computedAt)
        put("base", plan.base)
        put("baseIsEquity", plan.baseIsEquity)
        put("invested", plan.invested)
        plan.liquidity?.let { put("liquidity", it) }
        put("headline", plan.headline)
        put("marketNote", plan.marketNote)
        put("freedCash", plan.freedCash)
        put("cashFloorPct", plan.cashFloorPct)
        put("cashFloorValue", plan.cashFloorValue)
        put("targetCashPct", plan.targetCashPct)
        put("cashNote", plan.cashNote)
        put("policyNote", plan.policyNote)
        put("caveat", plan.caveat)
        put("notes", JSONArray(plan.notes))
        put("targets", JSONArray().apply {
            plan.targets.forEach { t ->
                put(JSONObject().apply {
                    put("symbol", t.symbol); put("name", t.name); put("sector", t.sector)
                    put("curPct", t.currentPct); put("tgtPct", t.targetPct)
                    put("curVal", t.currentValue); put("tgtVal", t.targetValue)
                    put("delta", t.deltaValue); put("shares", t.approxShares)
                    put("move", t.move.name); put("conv", t.conviction)
                    put("convMax", t.convictionMax); put("cappedBy", t.cappedBy)
                    put("note", t.note)
                })
            }
        })
        put("adds", JSONArray().apply {
            plan.adds.forEach { l ->
                put(JSONObject().apply {
                    put("rank", l.rank); put("symbol", l.symbol); put("name", l.name)
                    put("sector", l.sector); put("amount", l.amount)
                    put("approxShares", l.approxShares); put("price", l.price)
                    put("confidence", l.confidence)
                    put("rationale", JSONArray(l.rationale))
                })
            }
        })
        put("sectorTargets", JSONArray().apply {
            plan.sectorTargets.forEach { s ->
                put(JSONObject().apply {
                    put("sector", s.sector); put("currentPct", s.currentPct)
                    put("targetPct", s.targetPct); put("flow", s.flow.name)
                    put("note", s.note)
                })
            }
        })
    }

    fun fromJson(o: JSONObject): AllocationPlan? = try {
        val targets = ArrayList<AllocationTarget>()
        o.optJSONArray("targets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                targets.add(
                    AllocationTarget(
                        symbol = t.getString("symbol"),
                        name = t.optString("name", ""),
                        sector = t.optString("sector", PortfolioLens.UNCLASSIFIED),
                        currentPct = t.getDouble("curPct"),
                        targetPct = t.getDouble("tgtPct"),
                        currentValue = t.getDouble("curVal"),
                        targetValue = t.getDouble("tgtVal"),
                        deltaValue = t.getDouble("delta"),
                        approxShares = t.optDouble("shares", 0.0),
                        move = AllocationMove.valueOf(t.getString("move")),
                        conviction = t.optInt("conv", 0),
                        convictionMax = t.optInt("convMax", 0),
                        cappedBy = t.optString("cappedBy", ""),
                        note = t.optString("note", "")
                    )
                )
            }
        }
        val adds = ArrayList<LiquidityAllocationLine>()
        o.optJSONArray("adds")?.let { arr ->
            for (i in 0 until arr.length()) {
                val l = arr.getJSONObject(i)
                val rationale = ArrayList<String>()
                l.optJSONArray("rationale")?.let { r ->
                    for (j in 0 until r.length()) rationale.add(r.optString(j))
                }
                adds.add(
                    LiquidityAllocationLine(
                        rank = l.optInt("rank", i + 1),
                        symbol = l.getString("symbol"),
                        name = l.optString("name", ""),
                        sector = l.optString("sector", PortfolioLens.UNCLASSIFIED),
                        amount = l.getDouble("amount"),
                        approxShares = l.optDouble("approxShares", 0.0),
                        price = l.getDouble("price"),
                        confidence = l.optInt("confidence", 0),
                        rationale = rationale
                    )
                )
            }
        }
        val sectorTargets = ArrayList<SectorAllocationTarget>()
        o.optJSONArray("sectorTargets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                sectorTargets.add(
                    SectorAllocationTarget(
                        sector = s.getString("sector"),
                        currentPct = s.getDouble("currentPct"),
                        targetPct = s.getDouble("targetPct"),
                        flow = runCatching { FlowVerdict.valueOf(s.optString("flow")) }
                            .getOrDefault(FlowVerdict.NEUTRAL),
                        note = s.optString("note", "")
                    )
                )
            }
        }
        val notes = ArrayList<String>()
        o.optJSONArray("notes")?.let { arr ->
            for (i in 0 until arr.length()) notes.add(arr.optString(i))
        }
        AllocationPlan(
            computedAt = o.getLong("computedAt"),
            base = o.getDouble("base"),
            baseIsEquity = o.getBoolean("baseIsEquity"),
            invested = o.getDouble("invested"),
            // Absent means "cash was not tracked", which is a real state — not a zero.
            liquidity = if (o.has("liquidity")) o.getDouble("liquidity") else null,
            headline = o.optString("headline", ""),
            marketNote = o.optString("marketNote", ""),
            targets = targets,
            adds = adds,
            sectorTargets = sectorTargets,
            freedCash = o.getDouble("freedCash"),
            cashFloorPct = o.getDouble("cashFloorPct"),
            cashFloorValue = o.getDouble("cashFloorValue"),
            targetCashPct = o.getDouble("targetCashPct"),
            cashNote = o.optString("cashNote", ""),
            notes = notes,
            policyNote = o.optString("policyNote", ""),
            caveat = o.optString("caveat", "")
        )
    } catch (_: Exception) {
        null
    }
}
