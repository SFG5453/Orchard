//! Stateless-by-default DSP primitives.
//!
//! Nothing here decides *what* a transition should do — that belongs to [`crate::planner`].
//! These are the building blocks a plan is rendered with.

pub mod automation;
pub mod fade;
pub mod filters;
pub mod gain;
pub mod mixer;
