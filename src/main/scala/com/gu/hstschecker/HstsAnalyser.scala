package com.gu.hstschecker
/**
  * Script to analyse a bind file and the domains within for HSTS readiness
  */

import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.services.route53.{AmazonRoute53, AmazonRoute53ClientBuilder}
import com.gu.hstschecker.connection.ResultPair
import com.gu.hstschecker.dns.{BindFile, Record, Route53, Zone}
import com.gu.hstschecker.dns.Route53.Route53Client
import com.gu.hstschecker.reports._
import com.gu.hstschecker.util.{CliOptionsFailure, Failure}

object HstsAnalyser {

  type Result = (Record, ResultPair)
  type ReportGenerator = List[Result] => Option[Report]

  def main(args: Array[String]) {
    CliOptions.parser.parse(args, CliOptions()) match {
      case Some(options) =>
        val zoneOrFailure: Either[Failure, Zone] = options match {
          case CliOptions(_, _, _, Some(bindFile), _, _, _) =>
            val inputStream = BindFile.loadBindFile(bindFile)
            BindFile.parseBindData(inputStream)

          case CliOptions(_, verbose, _, _, Some(r53Zone), Some(awsRegion), profiles) =>
            val credentialsProviders = if (profiles.nonEmpty) {
              profiles.map{ profile =>
                profile -> new ProfileCredentialsProvider(profile)
              }.toList
            } else List("default" -> new DefaultAWSCredentialsProviderChain())

            implicit val route53: List[Route53Client] = credentialsProviders.map { case (name, credentialsProvider) =>
              Route53Client(name, AmazonRoute53ClientBuilder
                .standard()
                .withRegion(awsRegion)
                .withCredentials(credentialsProvider)
                .build())
            }

            Route53.getZone(r53Zone, verbose)
          case _ => Left(CliOptionsFailure("Incorrect options, unable to make sense of what you typed."))
        }

        val resultsOrFailure = for {
          zone <- zoneOrFailure
        } yield {
          if (options.verbose) {
            System.err.println(zone)
          }

          AandCNAME.report(zone, options.output, options.verbose, options.limit) ::
          DelegatedZones.report(zone) ::
          Wildcard.report(zone) ::
          DNAME.report(zone) ::
          AAAA.report(zone) ::
          Preload.report(zone) ::
          Nil
        }

        resultsOrFailure match {
          case Right(results) =>
            System.err.println()
            Report.printReports(results)
          case Left(CliOptionsFailure(_)) =>
            System.err.println(CliOptions.parser.usage)
          case Left(otherFailure) =>
            System.err.println(s"Failed to complete check: $otherFailure")
        }
      case None => System.err.println(CliOptions.parser.usage)
    }
  }
}