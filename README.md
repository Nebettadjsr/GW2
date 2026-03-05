# Nebet's GW2 Tool

A desktop tool that analyzes **Guild Wars 2 crafting profitability** by combining account data, learned recipes, and Trading Post prices to identify profitable crafting opportunities.

The application imports data from the official **Guild Wars 2 API**, stores it in a **PostgreSQL database**, and performs local analysis to determine which recipes can generate profit from the materials you already own.

---

# Overview

Nebet's GW2 Tool is a Java desktop application designed to help Guild Wars 2 players make better economic decisions by analyzing crafting, materials, and Trading Post prices.

The application connects to the official **Guild Wars 2 API**, stores relevant game data in a **PostgreSQL database**, and performs local calculations to evaluate crafting opportunities and material value across the entire account.

The tool focuses on three main use cases:

- **Crafting Profit Analysis**  
  Identify items that can be crafted from your current materials and determine whether crafting them is more profitable than selling the materials directly.

- **Crafting Discovery Assistance**  
  Help characters efficiently discover new crafting recipes by showing undiscovered recipes, their material costs, and their potential Trading Post value.

- **Economic Calculations for Game Systems**  
  Analyze specific mechanics such as salvaging *Glob of Ectoplasm* to determine the real gold cost of gaining **Magic Find** through Essences of Luck.

By combining account inventory data, recipe knowledge, and Trading Post prices, the tool allows players to convert accumulated materials into profitable crafting outcomes and make informed decisions about how to use their resources.

After the initial synchronization, most analysis is performed **locally**, meaning the application can run quickly and efficiently with only occasional API refreshes required for updated prices or account data.
---

# Technology Stack

- **Language:** Java
- **IDE:** IntelliJ IDEA
- **Database:** PostgreSQL
- **External API:** Guild Wars 2 Official API (ArenaNet)

---

# Database Setup

The project requires a **PostgreSQL database**.

A complete SQL script for creating the database schema is included in the source code:

```
PostgreSQL Query to create DB
```

Run this query in your PostgreSQL environment before starting the application.

---

# Configuration

To use the GW2 API you must provide your **ArenaNet API key**.

Open the following file:

```
src/repo/AppConfig
```

Configure:

- Your **GW2 API Key**
- PostgreSQL **database URL**
- PostgreSQL **username**
- PostgreSQL **password**

Example configuration parameters are already present in the file.

---

# First-Time Initialization

After starting the application you will see a button on the start screen:

```
First-time DB Setup (Base Fill)
```

This process will:

- Download the full item catalog
- Download all discoverable crafting recipes
- Import account data
- Populate the database tables

⚠️ This step can take **a long time** because it builds the full dataset required for the application.

However it only needs to be executed **once for initial setup**.

---

# Running the Application

After the database has been initialized, the application can be used normally.

The tool allows you to:

- Sync account data
- Refresh Trading Post prices
- Analyze crafting profitability
- Discover new crafting opportunities

---

# Features

## Crafting Profit View

This view answers the core question of the application:

> *"What can I craft with my current materials that is more profitable than selling the materials directly?"*

The user can filter results by:

- **All crafting disciplines**
- A **specific crafting discipline**
- A **specific character + discipline**

For every craftable recipe the table displays:

- **Material sell value** (total Trading Post value of required ingredients)
- **Item sell price** (Trading Post sell value of the crafted item)
- **Profit per craft**

```
profit_per_craft = item_sell_price - materials_sell_value
```

- **Craftable count** (how many times the recipe can be crafted with available materials)
- **Total profit**

```
total_profit = craftable_count × profit_per_craft
```

### Practical Use

Many crafting results appear unprofitable when viewed individually.

However, while playing the game large quantities of common materials accumulate over time (for example wood, ore, cabbage, etc.).

Even if the profit per craft is small, being able to craft **dozens or hundreds of items** can turn those materials into a significant amount of gold.

Example situations:

- crafting potions with cheap materials
- converting excess harvesting materials into gold
- clearing large stacks of bank materials

The Crafting Profit View helps identify these opportunities and convert **unused material stockpiles into profitable items**.

---

## Crafting Discover Helper

This view helps players discover new crafting recipes efficiently.

The user selects:

- a **character**
- a **crafting discipline**

The tool then lists all recipes that:

- can be discovered by combining materials
- are **not learned yet** by the selected character
- are **not vendor-learned recipes** (scroll recipes are excluded)

For each discoverable recipe the tool shows:

- cost of potentially missing materials
- expected Trading Post sell value of the crafted item
- profit per craft (sell value − material cost)

Additional behavior:

- Recipes never exceed the **current crafting level** of the selected character.
- Recipes close to the character’s crafting level give the most **XP for leveling**.

The list can be sorted by:

- item sell price
- crafting profit

This allows the user to either:

- level crafting efficiently
- or search for profitable discoveries.

---

## Salvage Ecto for Dust & Luck

This tool estimates the **real gold cost of increasing Magic Find** by salvaging *Glob of Ectoplasm*.

Salvaging ectos produces:

- Essences of Luck (used to increase Magic Find)
- Crystalline Dust

The tool uses:

- current Trading Post prices
- user-tested drop rate estimates

to calculate:

- the **expected gold loss per salvaged ecto**
- the **effective cost to obtain 1000 Luck**

Prices on this view are always based on **fresh Trading Post prices** that are fetched when the page is opened.

---

## Important Notes About Account Materials

For calculation purposes the application treats **all materials across the account as one combined pool**.

This includes:

- materials stored in the **bank**
- materials stored in **character inventories**

Because of this:

- the tool may assume materials are available even if they are stored on a different character.

Example:

If one character holds 500 wood in their inventory but the bank is empty, the application will still assume that **500 wood are available for crafting calculations**.

However, the in-game crafting interface may not allow crafting until those materials are moved to the correct location.

---

## API Synchronization Delay

After performing actions in-game such as:

- discovering recipes
- consuming materials

it may take **several minutes** until the Guild Wars 2 API reflects those changes.

During that time the application may temporarily display outdated data.

---

# Known Limitations

## Guild Wars 2 API Request Limits

The Guild Wars 2 API applies request limits to API endpoints.

To stay within those limits and keep synchronization fast and reliable, the application intentionally **does not fetch Trading Post prices for every possible item**.

Instead, the application only requests prices for **items that are relevant to the currently used view**.

This design dramatically reduces API load and prevents common issues such as:

- API rate limiting (HTTP 429)
- slow refresh times
- unnecessary requests for items that are never used in calculations

---

## Different TP Refresh Behavior Per View

The **"Refresh TP Prices"** button behaves differently depending on which view it is used in.

### Crafting Profit View

When refreshing prices in the Crafting Profit view, the application fetches prices only for:

- recipe outputs that can be crafted with the account
- ingredients required for those recipes

This keeps the refresh small and fast because it only updates prices that are relevant for profit calculations.

### Crafting Discovery Helper

The discovery view potentially needs data for a **much larger set of recipes**, because it analyzes recipes that have **not yet been discovered**.

As a result, a TP refresh in the discovery helper may request significantly more prices than in the profit view.

Even in this case, the application still filters the request set to avoid unnecessary API calls.

---

## Manual Price Refresh

Trading Post prices are refreshed **manually** using the refresh button.

Prices may therefore be several minutes old, which is generally acceptable because GW2 Trading Post prices usually do not change dramatically in very short timeframes.

---

# Future Improvements

This section will list planned improvements as the project evolves.