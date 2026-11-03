/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

//! Best Mix queue order. Every pair gets a cheap predicted cost; the whole path is optimized at
//! once, so the best transitions are not spent on the first few songs; then the shared planner
//! checks every adjacent pair and the path is re-optimized around what it actually found.
use crate::{best_mix_cost::{self, has_musical, number}, measured};
use serde_json::{Value, json};
use std::{collections::HashMap, sync::mpsc, thread};

const PLANNER_THREADS: usize = 3;
/// Planner calls per song, the same budget the greedy sort spent on its finalists.
const PLANS_PER_SONG: usize = 3;
/// Longest run of songs an or-opt move relocates.
const MAX_MOVE: usize = 3;

/// One planner session per thread; a round's pairs are independent, so they plan at once.
struct PlannerPool {
    jobs: Vec<mpsc::Sender<(usize, Value)>>,
    results: mpsc::Receiver<(usize, Result<Value, String>)>,
    threads: Vec<thread::JoinHandle<()>>,
}

impl PlannerPool {
    fn new(size: usize) -> Option<Self> {
        let (result_tx, results) = mpsc::channel();
        let (ready_tx, ready) = mpsc::channel();
        let mut senders = Vec::new();
        let mut threads = Vec::new();
        for _ in 0..size {
            let (job_tx, job_rx) = mpsc::channel::<(usize, Value)>();
            let (result_tx, ready_tx) = (result_tx.clone(), ready_tx.clone());
            // QuickJS allows 2 MiB of stack; Windows threads default to 1 MiB.
            let spawned = thread::Builder::new().name("best-mix-planner".into())
                .stack_size(8 * 1024 * 1024)
                .spawn(move || {
                    let Ok(mut planner) = orchard_transition_planner::PlannerSession::new() else {
                        let _ = ready_tx.send(false);
                        return;
                    };
                    let _ = ready_tx.send(true);
                    for (index, input) in job_rx {
                        if result_tx.send((index, planner.invoke("native", &input))).is_err() { break; }
                    }
                });
            let Ok(handle) = spawned else { continue };
            senders.push(job_tx);
            threads.push(handle);
        }
        drop(ready_tx);
        // Keep only workers whose session started; a failed one has already exited.
        let started: Vec<bool> = senders.iter().map(|_| ready.recv().unwrap_or(false)).collect();
        let jobs: Vec<_> = senders.into_iter().zip(started)
            .filter_map(|(sender, ok)| ok.then_some(sender)).collect();
        (!jobs.is_empty()).then_some(Self { jobs, results, threads })
    }

    fn plan(&self, inputs: Vec<Value>) -> Vec<Result<Value, String>> {
        let count = inputs.len();
        let mut output: Vec<Result<Value, String>> =
            (0..count).map(|_| Err("Planner worker stopped".into())).collect();
        let mut sent = 0;
        for (index, input) in inputs.into_iter().enumerate() {
            if self.jobs[index % self.jobs.len()].send((index, input)).is_ok() { sent += 1; }
        }
        for _ in 0..sent {
            let Ok((index, result)) = self.results.recv() else { break };
            output[index] = result;
        }
        output
    }
}

impl Drop for PlannerPool {
    fn drop(&mut self) {
        self.jobs.clear();
        for handle in self.threads.drain(..) { let _ = handle.join(); }
    }
}

/// Beat model confidence on a grid it agrees with (beats::grid caps it here). Playback
/// lifts Earmark's grid to this whenever the beat model agrees with it.
const CONFIRMED_BEAT_CONFIDENCE: f64 = 0.95;

/// Plans each pair the way playback will: tempo glide on, beats confirmed. A song an earlier
/// mix measured brings the beat model's real verdict (`beatModel`); for the rest sorting assumes
/// the confirmation playback gets for most songs.
pub(crate) fn plan_input(left: &Value, right: &Value) -> Value {
    let confirmed = |edge: &Value| {
        if let Some(mut measured) = measured::beat_model_edge(edge, &edge["beatModel"]) {
            measured.as_object_mut().map(|edge| edge.remove("beatModel"));
            return measured;
        }
        let mut edge = edge.clone();
        edge["beatConfidence"] = json!(number(&edge, "beatConfidence").max(CONFIRMED_BEAT_CONFIDENCE));
        edge
    };
    json!({"analysis":confirmed(left),"nextAnalysis":confirmed(right),
        "duration":number(left,"duration"),"nextDuration":number(right,"duration"),
        "tempoRamp":true})
}

/// False for edges built without audio (Apple Music's analysis): the planner needs spectra.
fn plannable(edge: &Value) -> bool {
    edge["plannable"].as_bool() != Some(false)
}

/// Costs of one musical run: `start[j]` from the song before it, `pair[i][j]` between its songs.
struct Costs {
    start: Vec<f64>,
    pair: Vec<Vec<f64>>,
}

impl Costs {
    fn path(&self, order: &[usize]) -> f64 {
        order.first().map_or(0.0, |&first| self.start[first])
            + order.windows(2).map(|w| self.pair[w[0]][w[1]]).sum::<f64>()
    }

    fn edge(&self, from: Option<usize>, to: usize) -> f64 {
        from.map_or(self.start[to], |from| self.pair[from][to])
    }
}

/// Nearest neighbour from the song before the run, then relocations until none helps.
fn optimize(costs: &Costs, seed: Option<Vec<usize>>) -> Vec<usize> {
    let count = costs.start.len();
    let mut order = seed.unwrap_or_else(|| {
        let mut left: Vec<usize> = (0..count).collect();
        let mut order = Vec::with_capacity(count);
        let mut previous = None;
        while !left.is_empty() {
            let best = (0..left.len())
                .min_by(|&a, &b| costs.edge(previous, left[a]).total_cmp(&costs.edge(previous, left[b])))
                .unwrap();
            let chosen = left.remove(best);
            order.push(chosen);
            previous = Some(chosen);
        }
        order
    });
    let mut best = costs.path(&order);
    loop {
        let mut improved = false;
        for length in 1..=MAX_MOVE.min(count.saturating_sub(1)) {
            for from in 0..=count - length {
                for to in 0..=count - length {
                    if to == from {
                        continue;
                    }
                    let mut candidate = order.clone();
                    let moved: Vec<usize> = candidate.drain(from..from + length).collect();
                    candidate.splice(to..to, moved);
                    let cost = costs.path(&candidate);
                    if cost + 1e-9 < best {
                        (order, best, improved) = (candidate, cost, true);
                    }
                }
            }
        }
        if !improved {
            return order;
        }
    }
}

/// Orders one musical run. `before` is the song it follows (None for the current song).
fn order_run<F>(run: &[usize], summaries: &[Value], before: Option<&Value>, before_index: Option<usize>,
                planner: Option<&PlannerPool>, fetch_pair: &mut F) -> Result<Vec<usize>, String>
where
    F: FnMut(Option<usize>, usize) -> Result<(Value, Value), String>,
{
    let start = run.iter().map(|&song| match before.filter(|edge| has_musical(edge)) {
        Some(edge) => best_mix_cost::predict(edge, &summaries[song]["head"]).1,
        None => 0.0,
    }).collect();
    let pair = run.iter().map(|&from| run.iter().map(|&to| {
        if from == to { f64::INFINITY } else { best_mix_cost::predict(&summaries[from]["tail"], &summaries[to]["head"]).1 }
    }).collect()).collect();
    let mut costs = Costs { start, pair };
    let mut order = optimize(&costs, None);
    let Some(planner) = planner else {
        return Ok(order.iter().map(|&local| run[local]).collect());
    };
    let mut verified: HashMap<(Option<usize>, usize), f64> = HashMap::new();
    let mut budget = PLANS_PER_SONG * run.len();
    // A run that follows nothing musical has no first pair to plan.
    let plans_start = before.is_some_and(has_musical);
    loop {
        let mut pending = Vec::new();
        for (position, &to) in order.iter().enumerate() {
            let from = position.checked_sub(1).map(|p| order[p]);
            let left = from.map_or(before, |from| Some(&summaries[run[from]]["tail"]));
            if (from.is_some() || plans_start) && !verified.contains_key(&(from, to))
                && left.is_some_and(plannable) && plannable(&summaries[run[to]]["head"]) {
                pending.push((from, to));
            }
        }
        if pending.is_empty() {
            return Ok(order.iter().map(|&local| run[local]).collect());
        }
        // Out of budget: a re-optimized order could lean on pairs nobody planned.
        let exhausted = pending.len() >= budget;
        pending.truncate(budget);
        budget -= pending.len();
        // Fetches stay sequential: the host answers one pair request at a time.
        let mut inputs = Vec::with_capacity(pending.len());
        for &(from, to) in &pending {
            let left = from.map(|local| run[local]).or(before_index);
            let (tail, head) = fetch_pair(left, run[to])?;
            inputs.push(plan_input(&tail, &head));
        }
        for (&(from, to), plan) in pending.iter().zip(planner.plan(inputs)) {
            let cost = plan.map_or(best_mix_cost::PLAY_OUT * 2.0, |plan| best_mix_cost::planned(&plan));
            verified.insert((from, to), cost);
            match from {
                Some(from) => costs.pair[from][to] = cost,
                None => costs.start[to] = cost,
            }
        }
        if exhausted {
            return Ok(order.iter().map(|&local| run[local]).collect());
        }
        order = optimize(&costs, Some(order));
    }
}

/// Sort from compact summaries; fetch full edge analyses only for pairs the planner checks.
/// Songs without musical analysis keep their queue positions.
pub fn sort_lazy<F>(summaries: &[Value], initial: &Value, mut fetch_pair: F) -> Result<Vec<usize>, String>
where
    F: FnMut(Option<usize>, usize) -> Result<(Value, Value), String>,
{
    // Hosts put each measured song's `beatModel` beside its summaries; planning reads it on the edges.
    let mut fetch_pair = |left: Option<usize>, right: usize| {
        let (mut tail, mut head) = fetch_pair(left, right)?;
        let model = |index: Option<usize>| index.map_or(&initial["beatModel"], |index| &summaries[index]["beatModel"]);
        if model(left).is_object() { tail["beatModel"] = model(left).clone(); }
        if model(Some(right)).is_object() { head["beatModel"] = model(Some(right)).clone(); }
        Ok((tail, head))
    };
    let planner = PlannerPool::new(PLANNER_THREADS);
    let mut output = Vec::with_capacity(summaries.len());
    let mut before = Some(initial);
    let mut before_index = None;
    let mut position = 0;
    while position < summaries.len() {
        if !has_musical(&summaries[position]["head"]) {
            output.push(position);
            before = None;
            position += 1;
            continue;
        }
        let first = position;
        while position < summaries.len() && has_musical(&summaries[position]["head"]) {
            position += 1;
        }
        let run: Vec<usize> = (first..position).collect();
        let ordered = order_run(&run, summaries, before, before_index, planner.as_ref(), &mut fetch_pair)?;
        before_index = ordered.last().copied();
        before = before_index.map(|index| &summaries[index]["tail"]);
        output.extend(ordered);
    }
    Ok(output)
}

pub fn sort(features: &[Value], initial: &Value) -> Vec<usize> {
    sort_lazy(features, initial, |left, right| {
        let left = left
            .map(|i| features[i]["tail"].clone())
            .unwrap_or_else(|| initial.clone());
        Ok((left, features[right]["head"].clone()))
    })
    .unwrap_or_else(|_| (0..features.len()).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn costs(start: Vec<f64>, pair: Vec<Vec<f64>>) -> Costs {
        Costs { start, pair }
    }

    #[test]
    fn the_whole_path_beats_spending_the_best_pair_first() {
        // Greedy takes A at no cost, then is left with a play-out into B.
        const X: f64 = f64::INFINITY;
        let costs = costs(vec![0.0, 1.0, 5.0], vec![
            vec![X, 20.0, 1.0],
            vec![1.0, X, 1.0],
            vec![1.0, 20.0, X],
        ]);
        let order = optimize(&costs, None);
        assert_eq!(costs.path(&order), 3.0);
        assert_eq!(order[0], 1);
    }

    #[test]
    fn optimizing_never_loses_to_the_greedy_start() {
        let mut state = 7u64;
        let mut random = || { state = state.wrapping_mul(6364136223846793005).wrapping_add(1); (state >> 33) as f64 / (1u64 << 31) as f64 };
        for _ in 0..20 {
            let count = 9;
            let start: Vec<f64> = (0..count).map(|_| random() * 20.0).collect();
            let pair: Vec<Vec<f64>> = (0..count).map(|i| (0..count)
                .map(|j| if i == j { f64::INFINITY } else { random() * 20.0 }).collect()).collect();
            let costs = costs(start, pair);
            let greedy = {
                let mut left: Vec<usize> = (0..count).collect();
                let mut order = Vec::new();
                let mut previous = None;
                while !left.is_empty() {
                    let best = (0..left.len()).min_by(|&a, &b|
                        costs.edge(previous, left[a]).total_cmp(&costs.edge(previous, left[b]))).unwrap();
                    previous = Some(left.remove(best));
                    order.push(previous.unwrap());
                }
                order
            };
            assert!(costs.path(&optimize(&costs, None)) <= costs.path(&greedy) + 1e-9);
        }
    }

    #[test]
    fn finalists_plan_like_playback_after_beat_model() {
        let input = plan_input(&json!({"duration": 200.0, "beatConfidence": 0.38}),
                               &json!({"duration": 180.0, "beatConfidence": 0.97}));
        assert_eq!(input["analysis"]["beatConfidence"], json!(CONFIRMED_BEAT_CONFIDENCE));
        assert_eq!(input["nextAnalysis"]["beatConfidence"], json!(0.97));
        assert_eq!(input["tempoRamp"], json!(true));
        // A measured song plans with what the beat model said, even when it said no.
        let doubted = json!({"duration": 200.0, "beatConfidence": 0.8,
            "beatModel": {"version": measured::VERSION, "beatConfidence": 0.45}});
        let input = plan_input(&doubted, &json!({"duration": 180.0}));
        assert_eq!(input["analysis"]["beatConfidence"], json!(0.45));
        assert!(input["analysis"].get("beatModel").is_none());
    }

    #[test]
    fn unavailable_analysis_keeps_its_queue_position() {
        let known = json!({"head":{"bpm":120.0},"tail":{"bpm":120.0}});
        assert_eq!(sort(&[known.clone(), Value::Null, known], &Value::Null), vec![0, 1, 2]);
    }

    #[test]
    fn tempo_clusters_stay_together() {
        let song = |bpm: f64| json!({"head": {"bpm": bpm, "duration": 150.0}, "tail": {"bpm": bpm, "duration": 150.0}});
        let summaries = vec![song(128.0), song(90.0), song(126.0), song(92.0), song(130.0), song(88.0)];
        // Predicted costs alone: these summaries carry no beat grid for the planner to read.
        let run: Vec<usize> = (0..summaries.len()).collect();
        let order = order_run(&run, &summaries, Some(&json!({"bpm": 127.0})), None, None,
            &mut |_, _| Err("no planner".to_owned())).unwrap();
        let tempos: Vec<f64> = order.iter().map(|&i| summaries[i]["head"]["bpm"].as_f64().unwrap()).collect();
        // One jump between the two tempo groups, never back and forth.
        let jumps = tempos.windows(2).filter(|w| best_mix_cost::tempo_deviation(w[0], w[1]).unwrap() > 0.08).count();
        assert_eq!(jumps, 1, "{tempos:?}");
        assert!(tempos[0] > 120.0);
    }

    #[test]
    fn the_planner_checks_every_adjacent_pair_within_budget() {
        let edge = json!({"duration":150.0,"bpm":120.0,"beatConfidence":0.8,
            "energyCurve":[{"time":0.0,"energy":0.5}]});
        let summaries = vec![json!({"head":edge,"tail":edge}); 8];
        let mut requests = Vec::new();
        let order = sort_lazy(&summaries, &edge, |left, right| {
            requests.push((left, right));
            Ok((edge.clone(), edge.clone()))
        }).unwrap();
        assert_eq!(order.len(), summaries.len());
        assert!(requests.len() >= summaries.len() && requests.len() <= PLANS_PER_SONG * summaries.len());
        let mut previous = None;
        for &song in &order {
            assert!(requests.contains(&(previous, song)), "pair {previous:?} -> {song} was never planned");
            previous = Some(song);
        }
    }

    #[test]
    fn edges_without_audio_sort_on_predictions_alone() {
        let edge = json!({"duration":150.0,"bpm":120.0,"beatConfidence":0.95,"plannable":false});
        let summaries = vec![json!({"head":edge,"tail":edge}); 6];
        let order = sort_lazy(&summaries, &edge, |_, _| Err("no pair should be fetched".to_owned())).unwrap();
        assert_eq!(order.len(), summaries.len());
    }
}
