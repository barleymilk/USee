package com.ssafy.animal.simulation.adapter.out.core

import com.ssafy.animal.simulation.application.port.out.AccountRow
import com.ssafy.animal.simulation.application.port.out.BreedRow
import com.ssafy.animal.simulation.application.port.out.CoreReader
import com.ssafy.animal.simulation.application.port.out.UserRow
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * core 스키마 읽기 전용 JOIN(Q420 D·Q482). simulation 접속은 currentSchema=simulation이라 core.를 붙인다.
 * simulation_app 계정에 core.users·animal_breeds·animal_species·images SELECT 권한이 필요하다.
 */
@Repository
class CoreJdbcReader(private val jdbc: NamedParameterJdbcTemplate) : CoreReader {

    override fun findBreed(breedSeq: Int): BreedRow? = findBreeds(listOf(breedSeq))[breedSeq]

    override fun findBreeds(breedSeqs: Collection<Int>): Map<Int, BreedRow> {
        if (breedSeqs.isEmpty()) return emptyMap()
        return jdbc.query(
            """
            SELECT b.breed_seq, b.breed_name, s.species_code, b.personality, i.image_key
            FROM core.animal_breeds b
            JOIN core.animal_species s ON s.species_seq = b.species_seq
            LEFT JOIN core.images i ON i.image_seq = b.representative_image_seq
            WHERE b.breed_seq IN (:seqs)
            """,
            mapOf("seqs" to breedSeqs),
            RowMapper { rs, _ ->
                BreedRow(rs.getInt("breed_seq"), rs.getString("breed_name"), rs.getString("species_code"), rs.getString("personality"), rs.getString("image_key"))
            },
        ).associateBy { it.breedSeq }
    }

    override fun findUsers(userSeqs: Collection<Int>): Map<Int, UserRow> {
        if (userSeqs.isEmpty()) return emptyMap()
        return jdbc.query(
            """
            SELECT u.user_seq, u.role, u.nickname, u.is_deleted, u.profile_image_seq, i.image_key
            FROM core.users u
            LEFT JOIN core.images i ON i.image_seq = u.profile_image_seq
            WHERE u.user_seq IN (:seqs)
            """,
            mapOf("seqs" to userSeqs),
            RowMapper { rs, _ ->
                UserRow(
                    userSeq = rs.getInt("user_seq"),
                    role = rs.getString("role"),
                    nickname = rs.getString("nickname"),
                    isDeleted = rs.getBoolean("is_deleted"),
                    profileImageSeq = rs.getLong("profile_image_seq").takeIf { !rs.wasNull() },
                    profileImageKey = rs.getString("image_key"),
                )
            },
        ).associateBy { it.userSeq }
    }

    override fun findAccount(userSeq: Int): AccountRow? = jdbc.query(
        "SELECT is_deleted, account_status FROM core.users WHERE user_seq = :userSeq",
        mapOf("userSeq" to userSeq),
        RowMapper { rs, _ -> AccountRow(rs.getBoolean("is_deleted"), rs.getString("account_status")) },
    ).firstOrNull()
}
