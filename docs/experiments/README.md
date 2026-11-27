# Experiments

Each experiment whose results the project reports has a folder here, `docs/experiments/<yyyymmdd>-<name>/`, dated by the day its runs started. The folder holds what is needed to check the results without sending a single model request: the run records, the summary, the terminal output of each run and a README that reports the question, the design and the findings. This page describes the method and indexes the experiments.

## Index

One row per experiment: folder, question in one sentence, number of runs. None has been published yet.

## Folder contents

- `README.md`: the report, following the [template](#readme-template).
- `summary.csv`: the summary per task and cell written by `scripts/summary.py`.
- `<cell>--<task>--rNN.json`: one [run record](../experiment.md#run-records) per run, judged by [`Evaluate`](../experiment.md#offline-evaluation).
- `logs/<cell>--<task>--rNN.log`: the terminal output of each run (tool calls with their arguments, tool results, final answer), and `logs/evaluate.log`.
- `errors/`: the records of runs that ended in an infrastructure error and were run again. They stay out of the summary but count in the cost.
- `pilot/`: the records of the trial runs made while designing the experiment, outside the matrix. They count in the cost.
- Plots, drawn by code in the repository from the records.

The debug logs (`logs/*.debug.log`) stay out: they hold the raw HTTP traffic, with every plot in base64, and outweigh everything else. `.gitignore` excludes them, and also lets the other `.log` files under `docs/experiments/` in.

The records are data: never edit them by hand. A correction, such as extracting a final program again or judging the records with a new oracle, is made by code in the repository and listed in the README's data notes with its commit.

## Process

1. Run the experiment under `runs/`, which git ignores, for example `scripts/matrix.sh runs/matrix-<date>`. Move the records of the trial runs that led to its design into the `pilot/` subfolder.
2. When `Evaluate` has judged every record and `summary.csv` is written, copy the folder into the repository without the debug logs and `pending/`:

   ```bash
   rsync -a --exclude='*.debug.log' --exclude=pending/ runs/matrix-<date>/ docs/experiments/<date>-<name>/
   ```

3. Write the README and open a pull request with the folder. The data of an experiment reaches `master` together with its README.

## README template

In English, with these sections in this order:

- **Question**: what the experiment asks, and the expected outcome.
- **Design**: model, tasks with their starting programs, cells (feedback condition and prompt pieces), runs per cell, round-trip budget, and the oracle criterion (`evaluation.criterion`).
- **Code version and commands**: the commits in the records (`commit` and `evaluation.commit`), any `dirty` record and why, and the exact commands that produced the data.
- **Results**: passed runs per task and cell, taken from `evaluation.passed` and never from `outcome`, with a confidence interval per cell (Wilson) and a test for every comparison the text claims (Fisher's exact test). Plots go here.
- **Cost**: model requests, input and output tokens, and time, including `errors/` and `pilot/`.
- **Data notes**: anomalies, repeated runs, and records judged or extracted again, with the commit that did it.
- **Follow-up analyses**: later checks on the same data, each with its date. New runs make a new experiment.
- **How to regenerate**: the commands that rebuild `evaluation` and `summary.csv` from the records, and the ones that would extend the experiment.

## Code version

Each record names the code that produced it: `commit` is the commit checked out in the worktree when the run ended, and `dirty` says whether the worktree had changes git does not ignore. `evaluation` holds the same two fields for the code that judged the record. Both come from the worktree, not from the build. A matrix builds once at the start, so don't commit or edit files in its worktree while it runs. Records written before these fields existed lack them, and their README states the commits instead.
