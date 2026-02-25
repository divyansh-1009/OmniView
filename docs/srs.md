# Software Requirements Specification

## OmniView (Version 3.0)

**Prepared by:**
Divyansh Yadav, Akhil Dhyani, Harshil Agrawal, Harshit Dhangar
**Course:** Software Engineering
**Date:** 27/02/2026

---

## Table of Contents

* Revision History
* Alpha Managers
* Requirements Traceability Matrix
* 1. Introduction
* 2. Overall Description
* 3. External Interface Requirements
* 4. System Features
* 5. Other Nonfunctional Requirements
* 6. Development Plan and Sprint Breakdown
* Appendix: Analysis Models

---

## Revision History

| Name                              | Date     | Reason for Changes                      | Version |
| --------------------------------- | -------- | --------------------------------------- | ------- |
| Permission handling update        | 28/01/26 | Support on-demand user control (REQ-2)  | 2       |
| Screenshot interval justification | 28/01/26 | Clarified interval and storage strategy | 2       |
| Blacklist feature update          | 28/01/26 | User-defined sensitive apps             | 2       |
| Architecture update               | 28/01/26 | Added search module and connections     | 2       |
| Timing diagram update             | 04/02/26 | Added work-based diagrams               | 2       |
| Traceability matrix               | 04/02/26 | Added for tracking requirements         | 2       |
| Alpha managers                    | 04/02/26 | Assigned team roles                     | 2       |
| Timeline feature added            | 04/02/26 | UI improvements                         | 2       |
| Requirements refined              | 04/02/26 | Updated REQ-3,4,5,10,14                 | 2       |
| Sprint plan added                 | 04/02/26 | Structured development plan             | 2       |
| Document conventions update       | 04/02/26 | Naming, commenting, structure           | 2       |
| UML refinement                    | 15/02/26 | Improved consistency and dependencies   | 3       |
| NFR structuring                   | 25/02/26 | Added identifiers for clarity           | 3       |
| Hash detection explanation        | 25/02/26 | Clarified REQ-1 mechanism               | 3       |
| Sprint timeline update            | 26/02/26 | Added completion dates                  | 3       |
| LaTeX migration                   | 27/02/26 | Formatting improvements                 | 3       |

---

## Alpha Managers

| Name            | Responsibility                |
| --------------- | ----------------------------- |
| Akhil Dhyani    | Requirements, Software System |
| Divyansh Yadav  | Work, Way of Working          |
| Harshil Agrawal | Stakeholders, Opportunity     |
| Harshit Dhangar | Team                          |

---

## Requirements Traceability Matrix

(Refer to table on page 6 of the original document for detailed mapping of requirements to modules and implementation stages.) 

---

# 1. Introduction

## 1.1 Purpose

This document specifies the software requirements for **OmniView**, an Android-based on-device screen understanding system. It defines system functionality, constraints, and development scope for the prototype developed as part of the Software Engineering course. 

---

## 1.2 Document Conventions

* Functional Requirements → `REQ-x`
* Non-functional Requirements → `NFR-x`
* Priority levels → High / Medium / Low

To ensure maintainability and consistency, OmniView follows a standardized coding and design structure.

### 1.2.1 Naming Conventions

* Classes: PascalCase

  * Example: `SnapshotManager`, `VectorEngine`
* Functions: camelCase (action-oriented)

  * Example: `captureScreen()`, `encodeText()`
* Variables: camelCase

  * Example: `isRecording`, `blacklistedApps`
* Constants: UPPER_SNAKE_CASE

  * Example: `CAPTURE_INTERVAL_MS`

---

### 1.2.2 File and Module Structure

Each major subsystem is organized into dedicated packages:

```
ingestion/
storage/
intelligence/
search/
ui/
```

---

### 1.2.3 Commenting and Documentation Style

* Function-level comments describe:

  * Intent
  * Inputs
  * Outputs
* Complex logic includes inline explanations
* Public interfaces use docstring-style comments

Example:

```kotlin
/**
 * Captures the current screen if permissions are granted
 * and the active application is not blacklisted.
 */
fun captureScreen(): Bitmap
```

---

## 1.3 Intended Audience and Reading Suggestions

This document is intended for faculty and evaluators.
Recommended reading flow:

1. Sections 1–2 (Overview)
2. Sections 4–5 (Detailed requirements)
3. Section 6 (Development plan)

---

## 1.4 Product Scope

OmniView is a standalone Android application that:

* Captures screen content periodically
* Extracts contextual information
* Stores vector embeddings locally
* Enables querying of historical activity

The system prioritizes **privacy, efficiency, and on-device intelligence**.

---

## 1.5 References

* Screenpipe (desktop alternative): https://screenpi.pe/

---

# 2. Overall Description

## 2.1 Product Perspective

OmniView is a self-contained mobile system designed as an alternative to desktop screen understanding tools. It follows a **modular architecture**, where storage exposes controlled interfaces for ingestion and retrieval.

---

## 2.2 Product Functions

* Periodic screenshot capture
* Context extraction using:

  * Android Accessibility API
  * Google ML Kit
  * Local models
* Vector embedding storage
* Query answering system
* Timeline visualization
* Permission policy management
* History deletion
* Battery monitoring
* Optional RAG-based responses

---

## 2.3 User Classes and Characteristics

* Single user class: **Device owner**
* Assumed to be non-technical
* Interaction is mostly passive

---

## 2.4 Operating Environment

* Platform: Android
* Language: Kotlin

---

## 2.5 Design and Implementation Constraints

* Battery usage constraints
* Storage limitations
* Android OS permission restrictions
* Background execution limits

---

## 2.6 User Documentation

A digital user manual will include:

* Installation steps
* Permission setup
* Usage instructions

---

## 2.7 Assumptions and Dependencies

* Permissions can be dynamically granted/revoked
* System pauses when permissions are revoked (REQ-2)
* Core functionality is fully on-device
* Internet required only for optional LLM queries

Dependencies:

* Android Accessibility APIs
* Google ML Kit

---

# 3. External Interface Requirements

## 3.1 User Interfaces

* Minimal UI interaction
* Background operation
* Chat-like interface for:

  * Queries
  * Timeline visualization

---

## 3.2 Hardware Interfaces

* Screen buffer access
* No microphone or sensor usage

---

## 3.3 Software Interfaces

* Android OS services
* SQLite / RoomDB
* NLP libraries

---

## 3.4 Communications Interfaces

* No network required for core functionality
* Optional LLM communication:

  * Only with user consent
  * No raw screenshots shared
  * Only contextual summaries transmitted

---

# 4. System Features

## 4.1 Screenshot Capture

### Description

Captures screenshots periodically based on visual changes.

**Priority:** High

### Functional Requirements

* **REQ-1:**

  * Capture screenshots every 10 seconds
  * Use perceptual hash-based change detection
  * Store only meaningful frames
  * Discard redundant frames
  * Delete raw screenshots after processing

* **REQ-2:**

  * Pause capture when permissions are revoked

---

## 4.2 Context Extraction and Processing

### Description

Extracts textual and contextual information and converts it into embeddings.

**Priority:** High

### Functional Requirements

* **REQ-3:** Extract text using Accessibility API
* **REQ-4:** Use ML Kit or local models (heavy tasks only while charging)
* **REQ-5:** Store context locally

---

## 4.3 Historical Search and Retrieval

### Description

Supports both keyword and semantic search using vector embeddings.

**Priority:** High

### Functional Requirements

* **REQ-6:** Keyword-based search
* **REQ-7:** Natural language search
* **REQ-8:** Rank results by relevance
* **REQ-9:** Perform search locally
* **REQ-10:** Optional RAG with external LLM

---

## 4.4 Sensitive Application Blacklisting

### Description

Prevents capture for sensitive apps.

**Priority:** High

### Functional Requirements

* **REQ-11:** Maintain blacklist
* **REQ-12:** Block capture for blacklisted apps
* **REQ-13:** Allow user to modify blacklist
* **REQ-14:** Allow manual capture stoppage

---

## 4.5 Timeline Visualization and Statistics

### Description

Provides chronological activity and analytics.

**Priority:** Low

### Functional Requirements

* **REQ-15:** Display activity timeline
* **REQ-16:** Show usage statistics

---

# 5. Other Nonfunctional Requirements

* **NFR-1:** Efficient capture with adaptive throttling
* **NFR-2:** No capture without explicit permission
* **NFR-3:** Local secure storage
* **NFR-4:**

  * Battery: < 3–5% per hour
  * Storage: < 1GB/week
  * Latency: < 200ms
* **NFR-5:** Only device owner access
* **NFR-6:** Fully local with optional external calls

---

# 6. Development Plan and Sprint Breakdown

## 6.1 Methodology

Agile sprint-based development (1–2 weeks per sprint).

---

## 6.2 Sprint Breakdown

### Sprint 1: Core Ingestion & Permissions

* REQ-1, REQ-2, REQ-11–14
* Duration: Feb 5 – Feb 28

---

### Sprint 2: Extraction & Storage

* REQ-3–5
* Duration: Mar 1 – Mar 9

---

### Sprint 3: Intelligence Engine

* REQ-7–9
* Duration: Mar 10 – Mar 20

---

### Sprint 4: Search & Visualization

* REQ-6, REQ-15–16
* Duration: Mar 21 – Mar 31

---

### Sprint 5: Optimization & Testing

* REQ-10 + NFRs
* Duration: Apr 1 – Apr 10

---

# Appendix: Analysis Models

Includes the following UML diagrams (see original document):

* Class Diagram
* Composite Diagram
* Component Diagram
* Use Case Diagram
* Activity Diagram
* Sequence Diagram
* Timing Diagram
* Deployment Diagram

Refer to pages 15–19 for diagrams. 

---
