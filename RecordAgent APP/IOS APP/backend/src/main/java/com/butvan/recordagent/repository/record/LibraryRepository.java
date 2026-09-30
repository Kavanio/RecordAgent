package com.butvan.recordagent.repository.record;

import com.butvan.recordagent.dto.record.LibraryDtos;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 所有资料读写都带 owner_id；更新以版本号防止覆盖另一设备。 */
@Repository
@RequiredArgsConstructor
public class LibraryRepository {
    private final JdbcTemplate jdbc;

    public List<LibraryDtos.Item> page(
            UUID owner,
            String type,
            boolean favorite,
            boolean trash,
            String query,
            int offset,
            int limit) {
        return jdbc.query(
                """
   SELECT id,record_date,record_type,COALESCE(title,''),left(content_text,180),length(content_text),favorite,trashed_at IS NOT NULL,version
   FROM record.record_entry WHERE owner_id=? AND (trashed_at IS NOT NULL)=? AND (?='' OR record_type=?)
   AND (NOT ? OR favorite) AND POSITION(lower(?) IN lower(COALESCE(title,'') || ' ' || content_text))>0
   ORDER BY pinned DESC,record_date DESC,created_at DESC,id DESC LIMIT ? OFFSET ?
   """,
                (r, n) ->
                        new LibraryDtos.Item(
                                r.getObject(1, UUID.class),
                                r.getObject(2, java.time.LocalDate.class),
                                r.getString(3),
                                r.getString(4),
                                r.getString(5),
                                r.getLong(6),
                                r.getBoolean(7),
                                r.getBoolean(8),
                                r.getLong(9)),
                owner,
                trash,
                type,
                type,
                favorite,
                query,
                limit,
                offset);
    }

    public Optional<LibraryDtos.Document> find(UUID owner, UUID id) {
        return jdbc
                .query(
                        "SELECT id,record_date,record_type,COALESCE(title,''),content_text,favorite,trashed_at IS NOT NULL,version FROM record.record_entry WHERE owner_id=? AND id=?",
                        (r, n) ->
                                new LibraryDtos.Document(
                                        r.getObject(1, UUID.class),
                                        r.getObject(2, java.time.LocalDate.class),
                                        r.getString(3),
                                        r.getString(4),
                                        r.getString(5),
                                        r.getBoolean(6),
                                        r.getBoolean(7),
                                        r.getLong(8)),
                        owner,
                        id)
                .stream()
                .findFirst();
    }

    public int create(UUID owner, LibraryDtos.Save s, Integer year, Integer week) {
        return jdbc.update(
                "INSERT INTO record.record_entry(id,owner_id,record_date,record_type,title,content_text,week_year,week_number) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",
                s.id(),
                owner,
                s.date(),
                s.type(),
                s.title().strip(),
                s.body(),
                year,
                week);
    }

    public int update(UUID owner, UUID id, LibraryDtos.Save s, Integer year, Integer week) {
        return jdbc.update(
                "UPDATE record.record_entry SET record_date=?,record_type=?,title=?,content_text=?,content_html='',week_year=?,week_number=?,version=version+1,updated_at=now() WHERE owner_id=? AND id=? AND version=? AND trashed_at IS NULL",
                s.date(),
                s.type(),
                s.title().strip(),
                s.body(),
                year,
                week,
                owner,
                id,
                s.version());
    }

    public int flags(UUID owner, UUID id, LibraryDtos.Flags s) {
        return jdbc.update(
                "UPDATE record.record_entry SET favorite=?,trashed_at=CASE WHEN ? THEN COALESCE(trashed_at,now()) ELSE NULL END,version=version+1,updated_at=now() WHERE owner_id=? AND id=? AND version=?",
                s.favorite(),
                s.trashed(),
                owner,
                id,
                s.version());
    }
}
