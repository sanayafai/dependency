package db

import javax.inject.{Inject, Singleton}
import anorm._
import com.google.inject.Provider
import io.flow.common.v0.models.UserReference
import io.flow.dependency.actors.MainActor
import io.flow.dependency.v0.models.{Library, LibraryForm}
import io.flow.postgresql.{OrderBy, Query}
import io.flow.util.IdGenerator
import play.api.db._

@Singleton
class LibrariesDao @Inject()(
  db: Database,
  dbHelpersProvider: Provider[DbHelpers],
  libraryVersionsDaoProvider: Provider[LibraryVersionsDao],
  @javax.inject.Named("main-actor") mainActor: akka.actor.ActorRef
){

  private[this] val BaseQuery = Query(s"""
    select libraries.id,
           libraries.group_id,
           libraries.artifact_id,
           organizations.id as organization_id,
           organizations.key as organization_key,
           resolvers.id as resolver_id,
           resolvers.visibility as resolver_visibility,
           resolvers.uri as resolver_uri,
           resolver_orgs.id as resolver_organization_id,
           resolver_orgs.key as resolver_organization_key
      from libraries
      join organizations on organizations.id = libraries.organization_id
      join resolvers on resolvers.id = libraries.resolver_id
      left join organizations resolver_orgs on resolver_orgs.id = resolvers.organization_id
  """)

  private[this] val InsertQuery = """
    insert into libraries
    (id, organization_id, group_id, artifact_id, resolver_id, updated_by_user_id)
    values
    ({id}, {organization_id}, {group_id}, {artifact_id}, {resolver_id}, {updated_by_user_id})
  """

  private[db] def validate(
    form: LibraryForm,
    existing: Option[Library] = None
  ): Seq[String] = {
    val groupIdErrors = if (form.groupId.trim.isEmpty) {
      Seq("Group ID cannot be empty")
    } else {
      Nil
    }

    val artifactIdErrors = if (form.artifactId.trim.isEmpty) {
      Seq("Artifact ID cannot be empty")
    } else {
      Nil
    }

    val existsErrors = if (groupIdErrors.isEmpty && artifactIdErrors.isEmpty) {
      findByGroupIdAndArtifactId(Authorization.All, form.groupId, form.artifactId) match {
        case None => Nil
        case Some(lib) => {
          if (Some(lib.id) == existing.map(_.id)) {
            Nil
          } else {
            Seq("Library with this group id and artifact id already exists")
          }
        }
      }
    } else {
      Nil
    }

    groupIdErrors ++ artifactIdErrors ++ existsErrors
  }

  def upsert(createdBy: UserReference, form: LibraryForm): Either[Seq[String], Library] = {
    findByGroupIdAndArtifactId(Authorization.All, form.groupId, form.artifactId) match {
      case None => {
        create(createdBy, form)
      }
      case Some(lib) => {
        db.withConnection { implicit c =>
          form.version.foreach { version =>
            libraryVersionsDaoProvider.get.upsertWithConnection(createdBy, lib.id, version)
          }
        }
        Right(lib)
      }
    }
  }

  def create(createdBy: UserReference, form: LibraryForm): Either[Seq[String], Library] = {
    validate(form) match {
      case Nil => {
        val id = IdGenerator("lib").randomId()

        db.withTransaction { implicit c =>
          SQL(InsertQuery).on(
            'id -> id,
            'organization_id -> form.organizationId,
            'group_id -> form.groupId.trim,
            'artifact_id -> form.artifactId.trim,
            'resolver_id -> form.resolverId,
            'updated_by_user_id -> createdBy.id
          ).execute()
          form.version.foreach { version =>
            libraryVersionsDaoProvider.get.upsertWithConnection(createdBy, id, version)
          }
        }

        mainActor ! MainActor.Messages.LibraryCreated(id)

        Right(
          findById(Authorization.All, id).getOrElse {
            sys.error("Failed to create library")
          }
        )
      }
      case errors => Left(errors)
    }
  }

  def delete(deletedBy: UserReference, library: Library): Unit = {
    libraryVersionsDaoProvider.get.findAll(
      Authorization.All,
      libraryId = Some(library.id),
      limit = None
    ).foreach { lv =>
      libraryVersionsDaoProvider.get.delete(deletedBy, lv)
    }

    dbHelpersProvider.get.delete("libraries", deletedBy.id, library.id)
    mainActor ! MainActor.Messages.LibraryDeleted(library.id)
  }

  def findByGroupIdAndArtifactId(
    auth: Authorization,
    groupId: String,
    artifactId: String
  ): Option[Library] = {
    findAll(
      auth,
      groupId = Some(groupId),
      artifactId = Some(artifactId),
      limit = 1
    ).headOption
  }

  def findById(auth: Authorization, id: String): Option[Library] = {
    findAll(auth, id = Some(id), limit = 1).headOption
  }

  def findAll(
    auth: Authorization,
    id: Option[String] = None,
    ids: Option[Seq[String]] = None,
    organizationId: Option[String] = None,
    projectId: Option[String] = None,
    groupId: Option[String] = None,
    artifactId: Option[String] = None,
    resolverId: Option[String] = None,
    orderBy: OrderBy = OrderBy("lower(libraries.group_id), lower(libraries.artifact_id), libraries.created_at"),
    limit: Long = 25,
    offset: Long = 0
  ): Seq[Library] = {
    db.withConnection { implicit c =>
      Standards.query(
        BaseQuery,
        tableName = "libraries",
        auth = auth.organizations("organizations.id", Some("resolvers.visibility")),
        id = id,
        ids = ids,
        orderBy = orderBy.sql,
        limit = limit,
        offset = offset
      ).
        equals("libraries.organization_id", organizationId).
        and(
          projectId.map { _ =>
            "libraries.id in (select library_id from project_libraries where project_id = {project_id})"
          }
        ).bind("project_id", projectId).
        optionalText(
          "libraries.group_id",
          groupId,
          columnFunctions = Seq(Query.Function.Lower),
          valueFunctions = Seq(Query.Function.Lower, Query.Function.Trim)
        ).
        optionalText(
          "libraries.artifact_id",
          artifactId,
          columnFunctions = Seq(Query.Function.Lower),
          valueFunctions = Seq(Query.Function.Lower, Query.Function.Trim)
        ).
        equals("libraries.resolver_id", resolverId).
        as(
          io.flow.dependency.v0.anorm.parsers.Library.parser().*
        )
    }
  }

}
