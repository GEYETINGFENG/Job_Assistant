# Design Notes

## 1. Version Number Strategy

Resume version numbers are generated using **atomic PostgreSQL SQL**.

The system does not use:

```text
Read latest_version_number
→ increment it in Java
→ write it back to the database
```

because concurrent requests could read the same version number and cause duplicate versions or conflicts.

The current approach lets the database complete the operation atomically:

```text
latest_version_number + 1
→ insert ResumeVersion
→ return the new version_number
```

It is also protected by:

```text
UNIQUE(resume_id, version_number)
```

as the final data-integrity constraint.

Atomic SQL is used because the version number is shared database state. Letting the database update it atomically avoids the race condition caused by a Java-side read-then-write process.

---

## 2. Pessimistic Locking

Pessimistic locking is used for:

```text
complete operations on the same uploadId
```

using:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
```

When two requests try to complete the same upload session at the same time:

```text
Request A → locks UploadSession → completes processing
Request B → waits for A → reads COMPLETED → does not process again
```

Pessimistic locking is used here because the same upload task should not be processed concurrently. The upload flow includes S3 operations, file parsing, and database writes, so duplicate execution would be expensive.

---

## 3. Optimistic Locking

Optimistic locking is used when editing an existing Resume Name


The Resume entity contains:

```java
@Version
private Long lockVersion;
```

Two requests can read and edit the same Resume concurrently.

If both initially read:

```text
lockVersion = 5
```

and request A updates first:

```text
lockVersion = 6
```

then request B still submits the stale value:

```text
lockVersion = 5
```

An optimistic locking conflict occurs during the update, preventing stale data from overwriting the newer change.

Optimistic locking is used because concurrent Resume edits are relatively uncommon. There is no need to lock the database row in advance; conflicts only need to be detected when an update is submitted.
