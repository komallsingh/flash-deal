package com.nnomm.deals

import com.nnomm.config.DatabaseFactory
import java.sql.Timestamp
import java.util.UUID

class DealRepository {

    fun create(deal: Deal): Deal {
        val sql = """
            INSERT INTO deals (
                id,
                restaurant_id,
                title,
                price_paise,
                stock_total,
                stock_available,
                starts_at,
                ends_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING *
        """.trimIndent()

        DatabaseFactory.dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->

                statement.setObject(1, deal.id)
                statement.setObject(2, deal.restaurantId)
                statement.setString(3, deal.title)
                statement.setInt(4, deal.pricePaise)
                statement.setInt(5, deal.stockTotal)
                statement.setInt(6, deal.stockAvailable)
                statement.setTimestamp(7, Timestamp.from(deal.startsAt))
                statement.setTimestamp(8, Timestamp.from(deal.endsAt))

                statement.executeQuery().use { result ->
                    check(result.next()) {
                        "Deal insertion returned no row"
                    }

                    return Deal(
                        id = result.getObject("id", UUID::class.java),
                        restaurantId = result.getObject("restaurant_id", UUID::class.java),
                        title = result.getString("title"),
                        pricePaise = result.getInt("price_paise"),
                        stockTotal = result.getInt("stock_total"),
                        stockAvailable = result.getInt("stock_available"),
                        startsAt = result.getTimestamp("starts_at").toInstant(),
                        endsAt = result.getTimestamp("ends_at").toInstant()
                    )
                }
            }
        }
    }

    fun findById(dealId: UUID): Deal? {
        val sql = """
            SELECT *
            FROM deals
            WHERE id = ?
        """.trimIndent()

        DatabaseFactory.dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->

                statement.setObject(1, dealId)

                statement.executeQuery().use { result ->
                    if (!result.next()) {
                        return null
                    }

                    return Deal(
                        id = result.getObject("id", UUID::class.java),
                        restaurantId = result.getObject("restaurant_id", UUID::class.java),
                        title = result.getString("title"),
                        pricePaise = result.getInt("price_paise"),
                        stockTotal = result.getInt("stock_total"),
                        stockAvailable = result.getInt("stock_available"),
                        startsAt = result.getTimestamp("starts_at").toInstant(),
                        endsAt = result.getTimestamp("ends_at").toInstant()
                    )
                }
            }
        }
    }
}