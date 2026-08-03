# Agent Context: LLM-in-the-Loop Workspace

This document serves as the standard context reference (or "Rule"/"Skill" base) for any Antigravity (AGY) agent operating in this workspace. It provides a structured table of contents and a technical map of the project to bootstrap agent context immediately.

## Table of Contents

1. [Project Definition](#1-project-definition)
2. [Directory Structure](#2-directory-structure)
3. [Key Entrypoints](#3-key-entrypoints)
4. [Tooling & Commands](#4-tooling--commands)
5. [Agent Workflow Guide](#5-agent-workflow-guide)

---

## 1. Project Definition

This repository contains a 2025 experiment integrating **Large Language Models (LLMs)** with **Spatial Computing Simulations (ScaFi/Alchemist)**.
The LLM acts as the "programmer in the loop," writing ScaFi programs and validating their emergent behaviors (like formations) by running simulations through a Model Context Protocol (MCP) server.

## 2. Directory Structure

- `mcp_server/`: The Scala-based MCP Server exposing compilation and simulation tools.
  - `src/main/scala/it/unibo/llm/mcp/server/`: Core server logic.
    - `ScafiMcpServer.scala`: Jetty-based MCP server implementation.
    - `utils/ScafiTestUtils.scala`: **CRITICAL FILE**. Handles in-memory compilation, dynamic class loading, Alchemist simulation execution, CSV parsing, and PNG plot generation.
    - `tool/ScafiTools.scala`: Spring AI version of the MCP tools.
  - `src/main/scala/it/unibo/scafi/`: ScaFi programs (e.g., `CircleFormation.scala`, `VFormation.scala`) used as tests or references.
  - `src/main/resources/`: Contains the Alchemist `swarmSimulation.yml` template and MCP JSON schemas.
- `simulation_agent/`: The Scala LangChain4j Agent.
  - `src/main/scala/it/unibo/llm/agent/Main.scala`: Agent entry point.
  - `src/main/scala/it/unibo/llm/agent/AgentTask.scala`: Setup for the LangGraph agent and MCP HTTP client.
- `simulation_agent_py/`: The Python LangChain Agent.
  - `scafi_agent.py`: Agent logic using `langchain-mcp-adapters` connecting to a local Ollama model.
- `build.mill`: The Mill build configuration for all modules.

## 3. Key Entrypoints

### Starting the MCP Server

- **Via Mill**: `./mill mcp_server.run` (or `./mill mcp_server.assembly` followed by `java -jar out/mcp_server/assembly.dest/out.jar`)
- **Via Docker**: `docker compose up`

### Starting the Agents

- **Scala Agent**: `./mill simulation_agent.run "<model-name>" "<task-description>"`
- **Python Agent**: `cd simulation_agent_py && python scafi_agent.py` (ensure requirements are met via `build.mill`).

## 4. Tooling & Commands

- **Build System**: Mill (`./mill`).
- **Dependencies**: Scala 2.13.17, Alchemist 42.3.7, ScaFi 1.6.0, Langchain4j 1.8.0, Spring Boot 3.5.7.
- **Port**: The MCP server listens on `http://localhost:8080/mcp`.

## 5. Agent Workflow Guide

When an AGY agent is asked to modify or debug this workspace, keep the following in mind:

1. **Compilation Issues**: The `ScafiTestUtils.scala` uses `scala.tools.nsc.Global`. If classpath errors occur during dynamic compilation, ensure `usejavacp.value = true` or explicitly pass the correct classpath.
2. **Simulation YAML**: Alchemist simulations are highly sensitive to YAML configuration. Any modifications to the simulation variables should be reflected in `mcp_server/src/main/resources/swarmSimulation.yml`.
3. **MCP Protocols**: The server uses HTTP SSE transport for MCP. Agents interacting with it must use a compatible HTTP/SSE MCP client setup, not stdio.
4. **Visual Output**: The `simulate_scafi` tool generates a base64 PNG using `nspl`. The LLM receives this image in the tool response. Make sure the testing LLM is a multimodal model (e.g., `qwen3-vl:2b` or `gemini-2.5-pro`) to process the plot successfully.
