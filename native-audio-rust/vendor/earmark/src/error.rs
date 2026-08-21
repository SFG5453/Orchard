use thiserror::Error;

pub type Result<T> = std::result::Result<T, CrossfadeError>;

#[derive(Debug, Error)]
#[non_exhaustive]
pub enum CrossfadeError {
    #[error("invalid audio: {0}")]
    InvalidAudio(String),

    #[error("invalid beat analysis: {0}")]
    InvalidBeatAnalysis(String),

    #[error("unsupported configuration: {0}")]
    UnsupportedConfiguration(String),

    #[error(
        "sample rate mismatch: outgoing is {outgoing} Hz, incoming is {incoming} Hz, and \
         resampling is disabled"
    )]
    SampleRateMismatch { outgoing: u32, incoming: u32 },

    #[error(
        "channel count mismatch: outgoing has {outgoing}, incoming has {incoming}, and neither \
         is mono"
    )]
    ChannelMismatch { outgoing: usize, incoming: usize },

    #[error("no viable transition found: {0}")]
    NoViableTransition(String),

    #[error("dsp failure: {0}")]
    Dsp(String),
}

impl CrossfadeError {
    pub(crate) fn audio(reason: impl Into<String>) -> Self {
        Self::InvalidAudio(reason.into())
    }

    pub(crate) fn beats(reason: impl Into<String>) -> Self {
        Self::InvalidBeatAnalysis(reason.into())
    }

    pub(crate) fn config(reason: impl Into<String>) -> Self {
        Self::UnsupportedConfiguration(reason.into())
    }

    pub(crate) fn dsp(reason: impl Into<String>) -> Self {
        Self::Dsp(reason.into())
    }
}

impl From<rubato::ResampleError> for CrossfadeError {
    fn from(value: rubato::ResampleError) -> Self {
        Self::Dsp(format!("resampler: {value}"))
    }
}

impl From<rubato::ResamplerConstructionError> for CrossfadeError {
    fn from(value: rubato::ResamplerConstructionError) -> Self {
        Self::Dsp(format!("resampler construction: {value}"))
    }
}

impl From<ebur128::Error> for CrossfadeError {
    fn from(value: ebur128::Error) -> Self {
        Self::Dsp(format!("loudness meter: {value:?}"))
    }
}
