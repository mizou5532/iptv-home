package home.iptv.shared.catalog

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/** Base SQLite du catalogue dans le stockage privé de l'app (`catalog.db`). */
fun catalogDriver(context: Context): SqlDriver =
    AndroidSqliteDriver(CatalogDatabase.Schema, context.applicationContext, "catalog.db")

fun catalogRepository(context: Context): CatalogRepository =
    CatalogRepository(CatalogDatabase(catalogDriver(context)))
