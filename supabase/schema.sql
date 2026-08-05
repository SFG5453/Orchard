-- ====================================================================
-- Orchard Account & Track Analysis Sync Schema
-- ====================================================================
-- This schema powers Orchard's cross-platform audio feature sync.
-- Deterministic audio analysis metadata (BPM, key, cue points, energy)
-- is stored by YouTube Video ID so all Orchard clients (Desktop & Mobile)
-- can share and retrieve analysis without redundant CPU/DSP processing.
-- ====================================================================

-- 1. Create track_analysis table
CREATE TABLE IF NOT EXISTS public.track_analysis (
    video_id TEXT PRIMARY KEY,
    duration DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    bpm DOUBLE PRECISION NOT NULL,
    musical_key TEXT DEFAULT '',
    key_confidence DOUBLE PRECISION DEFAULT 0.0,
    beat_confidence DOUBLE PRECISION DEFAULT 0.0,
    analysis_version INTEGER NOT NULL DEFAULT 9,
    analysis_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    analyzed_by UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT TIMEZONE('utc'::text, NOW()),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT TIMEZONE('utc'::text, NOW())
);

-- 2. Indexes for fast lookup
CREATE INDEX IF NOT EXISTS idx_track_analysis_bpm ON public.track_analysis(bpm);
CREATE INDEX IF NOT EXISTS idx_track_analysis_key ON public.track_analysis(musical_key);
CREATE INDEX IF NOT EXISTS idx_track_analysis_version ON public.track_analysis(analysis_version);

-- 3. Automatic updated_at trigger
CREATE OR REPLACE FUNCTION public.handle_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = TIMEZONE('utc'::text, NOW());
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS set_track_analysis_updated_at ON public.track_analysis;
CREATE TRIGGER set_track_analysis_updated_at
    BEFORE UPDATE ON public.track_analysis
    FOR EACH ROW
    EXECUTE FUNCTION public.handle_updated_at();

-- 4. Enable Row Level Security (RLS)
ALTER TABLE public.track_analysis ENABLE ROW LEVEL SECURITY;

-- 5. RLS Policies
-- READ: Anyone (authenticated or anon) can read track analysis data
CREATE POLICY "Allow public read on track_analysis"
    ON public.track_analysis
    FOR SELECT
    USING (true);

-- INSERT: Authenticated users can insert/upload new track analysis
CREATE POLICY "Allow authenticated users to insert track_analysis"
    ON public.track_analysis
    FOR INSERT
    TO authenticated
    WITH CHECK (true);

-- UPDATE: Authenticated users can update existing track analysis (e.g. newer analysis version)
CREATE POLICY "Allow authenticated users to update track_analysis"
    ON public.track_analysis
    FOR UPDATE
    TO authenticated
    USING (true)
    WITH CHECK (true);
