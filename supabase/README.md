# Orchard Supabase Cloud Sync Setup

Orchard uses Supabase for user accounts and cross-platform audio analysis synchronization.

## 1. Create a Free Supabase Project
1. Go to [supabase.com](https://supabase.com) and create a new project.
2. Under **Project Settings -> API**, copy your **Project URL** and **anon public key**.

## 2. Run Database Migration
1. Go to the **SQL Editor** in your Supabase dashboard.
2. Copy and run the contents of [`schema.sql`](schema.sql).

## 3. Configure Clients
You can configure the project URL and anon public key either via environment variables or directly in the app settings:
- Desktop: `VITE_SUPABASE_URL` and `VITE_SUPABASE_ANON_KEY` (or entered in Settings -> Account)
- Android: `ORCHARD_SUPABASE_URL` and `ORCHARD_SUPABASE_ANON_KEY` (or entered in Profile -> Orchard Account)
